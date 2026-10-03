#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <cstring>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <cerrno>
#include <dirent.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <sys/un.h>
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
 * 通过 fork() + execve() 启动 Proton 独立进程。
 *
 * 返回值约定：
 *   > 0  子进程 PID（execve 已成功）
 *   < 0  失败，返回 -errno（例如 -13 = EACCES、-8 = ENOEXEC）
 *
 * 实现要点：父进程通过一条 CLOEXEC 管道获知 execve 的真实 errno。
 * execve 成功时管道写端随 exec 自动关闭（read 返回 0）；失败时子进程写入 errno。
 * 这样 UI 才能拿到“权限被拒绝”这类根因，而不是只看到一个静默的退出码 127。
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

    // 解析可执行参数：argv[0] = 可执行文件路径，其余来自 argsArray
    std::vector<std::string> args_vec;
    args_vec.emplace_back(cmd_str);

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

    int err_pipe[2] = {-1, -1};
    if (pipe(err_pipe) != 0) {
        LOGE("pipe() failed! errno: %d", errno);
        int saved = errno;
        env->ReleaseStringUTFChars(command, cmd_str);
        if (log_str != nullptr) env->ReleaseStringUTFChars(logPath, log_str);
        return -saved;
    }
    // execve 成功后写端自动关闭（CLOEXEC），父进程因此能区分“成功”与“失败”
    fcntl(err_pipe[1], F_SETFD, FD_CLOEXEC);

    LOGI("Forking process for command: %s", cmd_str);
    pid_t pid = fork();

    if (pid == 0) {
        // ---- 子进程 ----
        close(err_pipe[0]);

        // 自建进程组：父进程可用 kill(-pid) 一次性终止 proot 及其所有 wine 子进程。
        // 否则 stopSession 只 SIGTERM 直接子进程，wine/wineserver 会变成孤儿继续跑。
        setpgid(0, 0);

        if (log_str != nullptr) {
            int log_fd = open(log_str, O_CREAT | O_WRONLY | O_APPEND, 0666);
            if (log_fd >= 0) {
                dup2(log_fd, STDOUT_FILENO);
                dup2(log_fd, STDERR_FILENO);
                close(log_fd);
            }
        }

        execve(cmd_str, exec_args.data(), exec_envs.data());

        int child_errno = errno;
        ssize_t ignored = write(err_pipe[1], &child_errno, sizeof(child_errno));
        (void) ignored;
        LOGE("execve failed for %s (errno: %d)", cmd_str, child_errno);
        _exit(127);
    }

    // ---- 父进程 ----
    close(err_pipe[1]);
    int child_errno = 0;
    ssize_t bytes = 0;
    do {
        bytes = read(err_pipe[0], &child_errno, sizeof(child_errno));
    } while (bytes < 0 && errno == EINTR);
    close(err_pipe[0]);

    env->ReleaseStringUTFChars(command, cmd_str);
    if (log_str != nullptr) {
        env->ReleaseStringUTFChars(logPath, log_str);
    }

    if (pid < 0) {
        int saved = errno;
        LOGE("fork() failed! errno: %d", saved);
        return -saved;
    }

    if (bytes == (ssize_t) sizeof(child_errno)) {
        // execve 失败：回收子进程并上报 errno
        waitpid(pid, nullptr, 0);
        return -child_errno;
    }

    LOGI("Spawned Proton child process with PID: %d", pid);
    return static_cast<jint>(pid);
}

/**
 * 探测任意路径的 unix socket 是否可连接。
 * 内嵌 libXlorie 的 X socket 位于 App 私有目录（$TMPDIR/.X11-unix/X<n>），不在 /tmp。
 */
JNIEXPORT jboolean JNICALL
Java_com_protondroid_NativeBridge_checkUnixSocket(JNIEnv *env, jobject /* this */, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    if (p == nullptr) return JNI_FALSE;

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    bool ok = false;

    if (strlen(p) < sizeof(addr.sun_path)) {
        strncpy(addr.sun_path, p, sizeof(addr.sun_path) - 1);
        int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (fd >= 0) {
            ok = connect(fd, reinterpret_cast<struct sockaddr *>(&addr), sizeof(addr)) == 0;
            close(fd);
        }
    }

    env->ReleaseStringUTFChars(path, p);
    LOGI("checkUnixSocket = %d", ok ? 1 : 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/**
 * 探测本地 X11 显示是否可达（Termux-X11 监听的是抽象 unix socket，
 * 抽象 socket 不经过文件系统权限检查，因此任意应用都可作为 X 客户端连接）。
 */
JNIEXPORT jboolean JNICALL
Java_com_protondroid_NativeBridge_checkX11Display(JNIEnv *env, jobject /* this */, jint display) {
    const int d = static_cast<int>(display);

    // 1) 抽象 socket: "@/tmp/.X11-unix/X<n>"
    {
        struct sockaddr_un addr;
        memset(&addr, 0, sizeof(addr));
        addr.sun_family = AF_UNIX;
        addr.sun_path[0] = '\0';
        snprintf(addr.sun_path + 1, sizeof(addr.sun_path) - 1, "/tmp/.X11-unix/X%d", d);
        socklen_t len = static_cast<socklen_t>(
                offsetof(struct sockaddr_un, sun_path) + 1 + strlen(addr.sun_path + 1));

        int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (fd >= 0) {
            bool ok = connect(fd, reinterpret_cast<struct sockaddr *>(&addr), len) == 0;
            close(fd);
            if (ok) {
                LOGI("X11 abstract socket for display :%d is reachable", d);
                return JNI_TRUE;
            }
        }
    }

    // 2) 文件系统 socket: "/tmp/.X11-unix/X<n>"
    {
        struct sockaddr_un addr;
        memset(&addr, 0, sizeof(addr));
        addr.sun_family = AF_UNIX;
        snprintf(addr.sun_path, sizeof(addr.sun_path), "/tmp/.X11-unix/X%d", d);

        int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (fd >= 0) {
            bool ok = connect(fd, reinterpret_cast<struct sockaddr *>(&addr), sizeof(addr)) == 0;
            close(fd);
            if (ok) {
                LOGI("X11 filesystem socket for display :%d is reachable", d);
                return JNI_TRUE;
            }
        }
    }

    LOGI("No X11 server reachable for display :%d", d);
    return JNI_FALSE;
}

/**
 * 终止指定 PID 的进程（同时终止其所在进程组，覆盖 proot 拉起的整个 wine 进程树）
 */
JNIEXPORT jboolean JNICALL
Java_com_protondroid_NativeBridge_killProcess(JNIEnv *env, jobject /* this */, jint pid, jint sig) {
    if (pid <= 0) return JNI_FALSE;
    int res = kill(static_cast<pid_t>(pid), sig);
    // 子进程在 execve 前调用了 setpgid(0, 0)，因此 pgid == pid
    int group_res = kill(-static_cast<pid_t>(pid), sig);
    LOGI("kill(pid=%d, sig=%d) returned: %d (process group: %d)", pid, sig, res, group_res);
    return res == 0 ? JNI_TRUE : JNI_FALSE;
}

static bool read_same_uid(FILE *status, uid_t my_uid) {
    char line[256];
    while (fgets(line, sizeof(line), status) != nullptr) {
        if (strncmp(line, "Uid:", 4) == 0) {
            unsigned int real_uid = 0;
            if (sscanf(line + 4, "%u", &real_uid) == 1) {
                return real_uid == static_cast<unsigned int>(my_uid);
            }
            return false;
        }
    }
    return false;
}

/**
 * 清理本应用遗留的运行时进程（proot / wine / wineserver / wineboot ...）。
 *
 * 场景：App 进程被系统回收后，proot 成为僵尸，其下的 wine 进程被 reparent 到
 * init 继续存活 —— 既占资源又锁住 wine prefix。
 *
 * 判据：
 *   1. activePid 仍存活 ⇒ 存在活跃会话，**整个清理直接跳过**。
 *      这里刻意不用“父进程链”判断：wine 的 wineserver / wineboot / winedevice
 *      本来就会 daemon 化（PPid=1），按父子关系会把活跃会话当成孤儿误杀。
 *   2. 否则按「同 uid + /proc/<pid>/exe 位于本应用私有目录」匹配并 SIGKILL。
 */
JNIEXPORT jint JNICALL
Java_com_protondroid_NativeBridge_cleanupStaleProcesses(
        JNIEnv *env, jobject /* this */, jstring filesDir, jint active_pid) {
    const char *prefix = env->GetStringUTFChars(filesDir, nullptr);
    const size_t prefix_len = strlen(prefix);
    const uid_t my_uid = getuid();
    const pid_t myself = getpid();
    int killed = 0;

    if (active_pid > 0 && active_pid != static_cast<jint>(myself)) {
        if (kill(static_cast<pid_t>(active_pid), 0) == 0) {
            LOGI("cleanupStaleProcesses: active session pid=%d alive, skip sweep", active_pid);
            env->ReleaseStringUTFChars(filesDir, prefix);
            return 0;
        }
    }

    DIR *proc_dir = opendir("/proc");
    if (proc_dir == nullptr) {
        env->ReleaseStringUTFChars(filesDir, prefix);
        return 0;
    }

    struct dirent *entry;
    while ((entry = readdir(proc_dir)) != nullptr) {
        if (entry->d_name[0] < '0' || entry->d_name[0] > '9') continue;
        const pid_t pid = static_cast<pid_t>(atoi(entry->d_name));
        if (pid <= 1 || pid == myself) continue;

        // 1) uid 校验
        char status_path[64];
        snprintf(status_path, sizeof(status_path), "/proc/%d/status", pid);
        FILE *status = fopen(status_path, "r");
        if (status == nullptr) continue;

        bool same_uid = read_same_uid(status, my_uid);
        fclose(status);
        if (!same_uid) continue;

        // 2) 可执行文件必须位于本应用私有目录内（避免误伤同 uid 的其他组件）
        char exe_link[64];
        snprintf(exe_link, sizeof(exe_link), "/proc/%d/exe", pid);
        char target[512];
        ssize_t len = readlink(exe_link, target, sizeof(target) - 1);
        if (len <= 0) {
            LOGI("sweep: pid=%d 读取 exe 失败 (errno=%d)，跳过", pid, errno);
            continue;
        }
        target[len] = '\0';

        if (strncmp(target, prefix, prefix_len) == 0) {
            kill(pid, SIGKILL);
            kill(-pid, SIGKILL);
            ++killed;
            LOGI("Cleaned up stale runtime process pid=%d (%s)", pid, target);
        }
    }

    closedir(proc_dir);
    env->ReleaseStringUTFChars(filesDir, prefix);
    LOGI("cleanupStaleProcesses killed %d process(es)", killed);
    return static_cast<jint>(killed);
}

/**
 * 轮询子进程状态 (非阻塞)
 *
 * 返回值编码（必须能区分“仍在运行”与“已成功退出且退出码为 0”）：
 *    0            仍在运行
 *    100 + code   已退出，code 为退出码 (0..255)
 *  -(100 + sig)  被信号 sig 终止
 *   -1           没有该子进程（已被回收）
 */
JNIEXPORT jint JNICALL
Java_com_protondroid_NativeBridge_waitPid(JNIEnv *env, jobject /* this */, jint pid) {
    if (pid <= 0) return -1;
    int status = 0;
    pid_t res = waitpid(static_cast<pid_t>(pid), &status, WNOHANG);
    if (res == 0) {
        return 0; // 仍在运行
    } else if (res > 0) {
        if (WIFEXITED(status)) {
            return 100 + WEXITSTATUS(status);
        } else if (WIFSIGNALED(status)) {
            return -(100 + WTERMSIG(status));
        }
        return 100;
    }
    return -1; // 进程不存在 / 已被回收
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
            ANativeWindow_setBuffersGeometry(g_native_window, 0, 0, WINDOW_FORMAT_RGBA_8888);
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

    if (buffer.bits != nullptr && buffer.stride > 0 && buffer.height > 0) {
        auto *pixels = static_cast<uint32_t *>(buffer.bits);
        int32_t safe_h = buffer.height;
        int32_t safe_w = buffer.width;
        for (int32_t y = 0; y < safe_h; ++y) {
            for (int32_t x = 0; x < safe_w; ++x) {
                pixels[y * buffer.stride + x] = static_cast<uint32_t>(color);
            }
        }
    }

    ANativeWindow_unlockAndPost(g_native_window);
    return JNI_TRUE;
}

} // extern "C"
