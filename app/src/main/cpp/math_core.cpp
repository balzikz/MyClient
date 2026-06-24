#include <jni.h>
#include <android/log.h>

#include <sstream>
#include <string>

namespace {

constexpr char kLogTag[] = "MATH-NATIVE";
constexpr char kCoreVersion[] = "0.3.0-alpha";

const char* detectAbi() {
#if defined(__aarch64__)
    return "arm64-v8a";
#elif defined(__arm__)
    return "armeabi-v7a";
#elif defined(__x86_64__)
    return "x86_64";
#elif defined(__i386__)
    return "x86";
#else
    return "unknown";
#endif
}

void logInfo(const std::string& message) {
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "%s", message.c_str());
}

jstring toJavaString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    logInfo("libmathclient.so loaded; JNI_OnLoad reached");
    return JNI_VERSION_1_6;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_balzikz_mathclient_NativeBridge_nativeInitialize(JNIEnv*, jclass) {
    std::ostringstream message;
    message << "Native core initialized"
            << "; version=" << kCoreVersion
            << "; abi=" << detectAbi();
    logInfo(message.str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_balzikz_mathclient_NativeBridge_nativeGetCoreInfo(JNIEnv* env, jclass) {
    std::ostringstream info;
    info << "Native Core: LOADED\n"
         << "Library: libmathclient.so\n"
         << "Core version: " << kCoreVersion << "\n"
         << "ABI: " << detectAbi() << "\n"
         << "C++ standard: C++20\n"
         << "JNI bridge: CONNECTED\n"
         << "Logcat tag: " << kLogTag;

    logInfo("Native core status requested through JNI");
    return toJavaString(env, info.str());
}
