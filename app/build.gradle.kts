plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.protondroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.protondroid"
        minSdk = 28
        // 必须保持 targetSdk = 28：Android 10 (API 29) 起对 targetSdk >= 29 的应用启用 W^X 限制，
        // 应用私有目录 (/data/user/0/<pkg>) 中的文件不再允许 execve()，也不允许可执行映射
        // (mmap PROT_EXEC)。Proton-droid 需要在私有沙箱里执行 PRoot 与 Proton/Wine 的 PE 镜像，
        // 因此必须落在 untrusted_app_27 域 (见 /system/etc/selinux/plat_seapp_contexts)。
        // 同样做法见 Termux (targetSdk 28) 与 Wine 官方 Android 移植
        // (wineandroid: "lower targetSdkVersion to avoid Android 10 W^X restrictions")。
        targetSdk = 28
        versionCode = 1
        versionName = "1.0.0-alpha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Proton 11 ARM64 专有目标
            abiFilters.add("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "27.2.12479018"

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
