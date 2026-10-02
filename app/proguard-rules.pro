# Proton-droid release 混淆规则
# 项目启用了 viewBinding + JNI，保持 JNI 入口与 Activity/Service 不被裁剪。

-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.protondroid.NativeBridge { *; }
-keep class com.protondroid.MainActivity { *; }
-keep class com.protondroid.ui.GameViewActivity { *; }
-keep class com.protondroid.service.ProtonForegroundService { *; }
-keep class com.protondroid.runtime.** { *; }
