#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <unistd.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <signal.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#define LOG_TAG "ProtonBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

/**
 * 获取系统内存分页大小 (Bytes)
 * 验证是否为标准 4KB (4096)
 */
JNIEXPORT jint JNICALL
Java_com_protondroid_NativeBridge_getSystemPageSize(JNIEnv *env, jobject /* this */) {
    long page_size = sysconf(_SC_PAGESIZE);
    LOGI("Current system page size: %ld Bytes", page_size);
    return static_cast<jint>(page_size);
}

/**
 * 检查 Mali GPU 设备节点 (/dev/mali0) 权限
 */
JNIEXPORT jboolean JNICALL
Java_com_protondroid_NativeBridge_checkGpuNodeAccess(JNIEnv *env, jobject /* this */) {
    const char *mali_dev = "/dev/mali0";
    if (access(mali_dev, R_OK | W_OK) == 0) {
        LOGI("Access to %s: GRANTED (R/W)", mali_dev);
        return JNI_TRUE;
    }
    LOGI("Access to %s: RESTRICTED or NOT FOUND", mali_dev);
    return JNI_FALSE;
}

/**
 * 通过 fork() + execve() 启动 Proton 独立进程，返回子进程 PID
 */
JNIEXPORT jint JNICALL
Java_com_protondroid_NativeBridge_forkAndExec(
        JNIEnv *env,
        jobject /* this */,
        jstring command,
        jobjectArray argsArray,
        jobjectArray envArray,
        jstring logPath) {

    const char *cmd_str = env->GetStringUTFChars(command, nullptr);
    const char *log_str = logPath != nullptr ? env->GetStringUTFChars(logPath, nullptr) : nullptr;

    // 解析可执行参数
    std::vector<std::string> args_vec;
    args_vec.push_back(cmd_str);

    int arg_count = env->GetArrayLength(argsArray);
    for (int i = 0; i < arg_count; ++i) {
        auto str_obj = (jstring) env->GetObjectArrayElement(argsArray, i);
        const char *raw = env->GetStringUTFChars(str_obj, nullptr);
        args_vec.emplace_back(raw);
        env->ReleaseStringUTFChars(str_obj, raw);
        env->DeleteLocalRef(str_obj);
    }

    std::vector<char *> exec_args;
    for (auto &s : args_vec) {
        exec_args.push_back(const_cast<char *>(s.c_str()));
    }
    exec_args.push_back(nullptr);

    // 解析环境变量
    std::vector<std::string> env_vec;
    int env_count = env->GetArrayLength(envArray);
    for (int i = 0; i < env_count; ++i) {
        auto str_obj = (jstring) env->GetObjectArrayElement(envArray, i);
        const char *raw = env->GetStringUTFChars(str_obj, nullptr);
        env_vec.emplace_back(raw);
        env->ReleaseStringUTFChars(str_obj, raw);
        env->DeleteLocalRef(str_obj);
    }

    std::vector<char *> exec_envs;
    for (auto &s : env_vec) {
        exec_envs.push_back(const_cast<char *>(s.c_str()));
    }
    exec_envs.push_back(nullptr);

    LOGI("Forking process for command: %s", cmd_str);
    pid_t pid = fork();

    if (pid == 0) {
        // 子进程环境
        // 重定向标准输出与错误输出到日志文件
        if (log_str != nullptr) {
            int log_fd = open(log_str, O_CREAT | O_WRONLY | O_APPEND, 0666);
            if (log_fd >= 0) {
                dup2(log_fd, STDOUT_FILENO);
                dup2(log_fd, STDERR_FILENO);
                close(log_fd);
            }
        }

        // 执行目标可执行程序 (e.g., wine, python3 proton_standalone.py)
        execve(cmd_str, exec_args.data(), exec_envs.data());

        // 如果 execve 返回则表示失败
        LOGE("execve failed for %s (errno: %d)", cmd_str, errno);
        _exit(127);
    } else if (pid < 0) {
        LOGE("fork() failed! errno: %d", errno);
    } else {
        LOGI("Spawned Proton child process with PID: %d", pid);
    }

    env->ReleaseStringUTFChars(command, cmd_str);
    if (log_str != nullptr) {
        env->ReleaseStringUTFChars(logPath, log_str);
    }

    return static_cast<jint>(pid);
}

/**
 * 终止指定 PID 的进程
 */
JNIEXPORT jboolean JNICALL
Java_com_protondroid_NativeBridge_killProcess(JNIEnv *env, jobject /* this */, jint pid, jint sig) {
    if (pid <= 0) return JNI_FALSE;
    int res = kill(static_cast<pid_t>(pid), sig);
    LOGI("kill(pid=%d, sig=%d) returned: %d", pid, sig, res);
    return res == 0 ? JNI_TRUE : JNI_FALSE;
}

/**
 * 轮询子进程状态 (非阻塞)
 */
JNIEXPORT jint JNICALL
Java_com_protondroid_NativeBridge_waitPid(JNIEnv *env, jobject /* this */, jint pid) {
    if (pid <= 0) return -1;
    int status = 0;
    pid_t res = waitpid(static_cast<pid_t>(pid), &status, WNOHANG);
    if (res == 0) {
        // 仍在运行
        return 0;
    } else if (res > 0) {
        // 已退出，返回退出码
        if (WIFEXITED(status)) {
            return WEXITSTATUS(status);
        } else if (WIFSIGNALED(status)) {
            return -WTERMSIG(status);
        }
        return 1;
    }
    return -1; // 进程不存在或错误
}

// -----------------------------------------------------------------------------
// SurfaceView / ANativeWindow 渲染直通模块
// -----------------------------------------------------------------------------

static std::mutex g_window_mutex;
static ANativeWindow* g_native_window = nullptr;

/**
 * 关联 Java 层 SurfaceView 的底层 Surface，获取 ANativeWindow 句柄
 */
JNIEXPORT jboolean JNICALL
Java_com_protondroid_NativeBridge_nativeSetSurface(JNIEnv *env, jobject /* this */, jobject surface) {
    std::lock_guard<std::mutex> lock(g_window_mutex);

    if (g_native_window != nullptr) {
        ANativeWindow_release(g_native_window);
        g_native_window = nullptr;
    }

    if (surface != nullptr) {
        g_native_window = ANativeWindow_fromSurface(env, surface);
        if (g_native_window != nullptr) {
            int32_t width = ANativeWindow_getWidth(g_native_window);
            int32_t height = ANativeWindow_getHeight(g_native_window);
            LOGI("ANativeWindow attached successfully! (Size: %dx%d)", width, height);
            return JNI_TRUE;
        } else {
            LOGE("Failed to acquire ANativeWindow from Surface!");
            return JNI_FALSE;
        }
    }
    return JNI_TRUE;
}

/**
 * 释放 ANativeWindow 句柄 (Surface 销毁时调用)
 */
JNIEXPORT void JNICALL
Java_com_protondroid_NativeBridge_nativeReleaseSurface(JNIEnv *env, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_window_mutex);
    if (g_native_window != nullptr) {
        LOGI("Releasing ANativeWindow...");
        ANativeWindow_release(g_native_window);
        g_native_window = nullptr;
    }
}

/**
 * 在 SurfaceView 上直接写入测试帧 (验证软/硬渲染管线打通)
 */
JNIEXPORT jboolean JNICALL
Java_com_protondroid_NativeBridge_nativeDrawTestPattern(JNIEnv *env, jobject /* this */, jint color) {
    std::lock_guard<std::mutex> lock(g_window_mutex);
    if (g_native_window == nullptr) return JNI_FALSE;

    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(g_native_window, &buffer, nullptr) < 0) {
        LOGE("ANativeWindow_lock failed!");
        return JNI_FALSE;
    }

    auto *pixels = static_cast<uint32_t *>(buffer.bits);
    for (int y = 0; y < buffer.height; ++y) {
        for (int x = 0; x < buffer.width; ++x) {
            pixels[y * buffer.stride + x] = static_cast<uint32_t>(color);
        }
    }

    ANativeWindow_unlockAndPost(g_native_window);
    return JNI_TRUE;
}

} // extern "C"
