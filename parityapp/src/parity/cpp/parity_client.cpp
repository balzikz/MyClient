#include <jni.h>
#include <android/log.h>

#include <mutex>
#include <string>

namespace {
constexpr const char* kTag = "MathParityClient";
std::mutex g_mutex;
jobject g_activity = nullptr;
std::string g_log_path;

void configure_client_logger(JNIEnv* env, jobject, jstring path) {
    if (path == nullptr) return;
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_log_path.assign(chars);
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "client logger=%s", chars);
    env->ReleaseStringUTFChars(path, chars);
}

void register_activity(JNIEnv* env, jobject, jobject activity) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_activity != nullptr) {
        env->DeleteGlobalRef(g_activity);
        g_activity = nullptr;
    }
    if (activity != nullptr) g_activity = env->NewGlobalRef(activity);
    __android_log_print(ANDROID_LOG_INFO, kTag, "activity registered=%s",
                        g_activity == nullptr ? "NO" : "YES");
}

constexpr JNINativeMethod kMethods[] = {
        {const_cast<char*>("nativeConfigureClientLogger"),
         const_cast<char*>("(Ljava/lang/String;)V"),
         reinterpret_cast<void*>(configure_client_logger)},
        {const_cast<char*>("nativeRegisterActivity"),
         const_cast<char*>("(Lcom/flarial/client/Launcher/MinecraftActivity;)V"),
         reinterpret_cast<void*>(register_activity)},
};
}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    if (vm == nullptr) return JNI_ERR;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK
            || env == nullptr) {
        return JNI_ERR;
    }

    jclass activity = env->FindClass("com/flarial/client/Launcher/MinecraftActivity");
    if (activity == nullptr) return JNI_ERR;
    if (env->RegisterNatives(activity, kMethods,
                             static_cast<jint>(sizeof(kMethods) / sizeof(kMethods[0]))) != JNI_OK) {
        env->DeleteLocalRef(activity);
        return JNI_ERR;
    }
    env->DeleteLocalRef(activity);
    __android_log_print(ANDROID_LOG_INFO, kTag, "minimal parity client JNI ready");
    return JNI_VERSION_1_6;
}
