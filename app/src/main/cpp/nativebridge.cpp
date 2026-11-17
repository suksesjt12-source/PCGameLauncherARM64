#include <jni.h>

extern "C"
JNIEXPORT jstring JNICALL
Java_com_suksesjt12_pcgamelauncher_NativeBridge_runtimeStatus(
    JNIEnv* env,
    jobject /* thiz */) {
    constexpr const char* status =
        "Native ARM64 bridge loaded. Wine/Box64 runtime is not bundled yet.";
    return env->NewStringUTF(status);
}
