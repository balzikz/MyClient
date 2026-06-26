#include <jni.h>
#include <android/log.h>
#include <android/native_activity.h>
#include <dlfcn.h>

#include <mutex>
#include <string>

namespace {
constexpr const char* kTag = "MathParityShin";

using NativeCreate = void (*)(ANativeActivity*, void*, size_t);
using NativeFinish = void (*)(ANativeActivity*);
using AndroidMain = void (*)(void*);

std::mutex g_mutex;
void* g_game_handle = nullptr;
NativeCreate g_native_create = nullptr;
NativeFinish g_native_finish = nullptr;
AndroidMain g_android_main = nullptr;
std::string g_log_path;

void log_line(int priority, const char* message) {
    __android_log_print(priority, kTag, "%s", message == nullptr ? "null" : message);
}

std::string take_utf(JNIEnv* env, jstring value) {
    if (env == nullptr || value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void configure_shim_logger(JNIEnv* env, jobject, jstring path) {
    std::string value = take_utf(env, path);
    if (value.empty()) {
        log_line(ANDROID_LOG_ERROR, "nativeConfigureShimLogger: invalid arguments");
        return;
    }
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_log_path = value;
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "Shim logger configured: %s", value.c_str());
}

void preload_library(JNIEnv* env, jobject, jstring path) {
    std::string value = take_utf(env, path);
    if (value.empty()) {
        log_line(ANDROID_LOG_ERROR, "nativePreloadLibrary: invalid arguments");
        return;
    }

    __android_log_print(ANDROID_LOG_INFO, kTag, "Loading native library: %s", value.c_str());
    dlerror();
    void* handle = dlopen(value.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (handle == nullptr) {
        const char* error = dlerror();
        __android_log_print(ANDROID_LOG_ERROR, kTag, "preload dlopen failed: %s",
                            error == nullptr ? "unknown" : error);
    }
}

void on_launcher_loaded(JNIEnv* env, jobject, jstring path) {
    std::string value = take_utf(env, path);
    if (value.empty()) {
        log_line(ANDROID_LOG_ERROR, "nativeOnLauncherLoaded: invalid arguments");
        return;
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_game_handle != nullptr) {
        log_line(ANDROID_LOG_INFO, "Native library already loaded, skipping");
        return;
    }

    __android_log_print(ANDROID_LOG_INFO, kTag, "Loading native library: %s", value.c_str());
    dlerror();
    void* handle = dlopen(value.c_str(), RTLD_NOW);
    if (handle == nullptr) {
        const char* error = dlerror();
        __android_log_print(ANDROID_LOG_ERROR, kTag, "dlopen failed: %s",
                            error == nullptr ? "unknown" : error);
        return;
    }

    dlerror();
    auto native_create = reinterpret_cast<NativeCreate>(
            dlsym(handle, "ANativeActivity_onCreate"));
    const char* create_error = dlerror();

    dlerror();
    auto native_finish = reinterpret_cast<NativeFinish>(
            dlsym(handle, "ANativeActivity_finish"));
    const char* finish_error = dlerror();

    dlerror();
    auto android_main = reinterpret_cast<AndroidMain>(
            dlsym(handle, "android_main"));
    const char* main_error = dlerror();

    g_game_handle = handle;
    g_native_create = native_create;
    g_native_finish = native_finish;
    g_android_main = android_main;

    if (native_create == nullptr || android_main == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, kTag,
                            "Legacy NativeActivity entrypoints unavailable; continuing GameActivity path "
                            "create=%p createError=%s finish=%p finishError=%s main=%p mainError=%s",
                            reinterpret_cast<void*>(native_create),
                            create_error == nullptr ? "none" : create_error,
                            reinterpret_cast<void*>(native_finish),
                            finish_error == nullptr ? "none" : finish_error,
                            reinterpret_cast<void*>(android_main),
                            main_error == nullptr ? "none" : main_error);
        return;
    }

    __android_log_print(ANDROID_LOG_INFO, kTag,
                        "Minecraft NativeActivity entrypoints ready create=%p finish=%p main=%p",
                        reinterpret_cast<void*>(native_create),
                        reinterpret_cast<void*>(native_finish),
                        reinterpret_cast<void*>(android_main));
}

JNINativeMethod kActivityMethods[] = {
        {const_cast<char*>("nativeConfigureShimLogger"),
         const_cast<char*>("(Ljava/lang/String;)V"),
         reinterpret_cast<void*>(configure_shim_logger)},
        {const_cast<char*>("nativePreloadLibrary"),
         const_cast<char*>("(Ljava/lang/String;)V"),
         reinterpret_cast<void*>(preload_library)},
        {const_cast<char*>("nativeOnLauncherLoaded"),
         const_cast<char*>("(Ljava/lang/String;)V"),
         reinterpret_cast<void*>(on_launcher_loaded)},
};

JNINativeMethod kServiceMethods[] = {
        {const_cast<char*>("nativeConfigureShimLogger"),
         const_cast<char*>("(Ljava/lang/String;)V"),
         reinterpret_cast<void*>(configure_shim_logger)},
        {const_cast<char*>("nativeOnLauncherLoaded"),
         const_cast<char*>("(Ljava/lang/String;)V"),
         reinterpret_cast<void*>(on_launcher_loaded)},
};

bool register_methods(JNIEnv* env, const char* class_name,
                      const JNINativeMethod* methods, jint count) {
    jclass type = env->FindClass(class_name);
    if (type == nullptr) {
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_WARN, kTag, "JNI class not found: %s", class_name);
        return false;
    }
    jint result = env->RegisterNatives(type, methods, count);
    env->DeleteLocalRef(type);
    if (result != JNI_OK) {
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_ERROR, kTag, "RegisterNatives failed: %s", class_name);
        return false;
    }
    return true;
}
}  // namespace

extern "C" JNIEXPORT void JNICALL
ANativeActivity_onCreate(ANativeActivity* activity, void* saved_state, size_t saved_state_size) {
    NativeCreate target;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        target = g_native_create;
    }
    if (target != nullptr) {
        target(activity, saved_state, saved_state_size);
    } else {
        log_line(ANDROID_LOG_WARN, "ANativeActivity_onCreate called without legacy target");
    }
}

extern "C" JNIEXPORT void JNICALL
ANativeActivity_finish(ANativeActivity* activity) {
    NativeFinish target;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        target = g_native_finish;
    }
    if (target != nullptr) target(activity);
}

extern "C" JNIEXPORT void JNICALL android_main(void* app) {
    AndroidMain target;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        target = g_android_main;
    }
    if (target != nullptr) {
        target(app);
    } else {
        log_line(ANDROID_LOG_WARN, "android_main called without legacy target");
    }
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    if (vm == nullptr) {
        log_line(ANDROID_LOG_ERROR, "JNI_OnLoad called with null VM");
        return JNI_ERR;
    }

    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK
            || env == nullptr) {
        return JNI_ERR;
    }

    register_methods(env,
                     "com/flarial/client/Launcher/MinecraftActivity",
                     kActivityMethods,
                     static_cast<jint>(sizeof(kActivityMethods) / sizeof(kActivityMethods[0])));
    register_methods(env,
                     "com/flarial/client/Launcher/MinecraftPreloadService",
                     kServiceMethods,
                     static_cast<jint>(sizeof(kServiceMethods) / sizeof(kServiceMethods[0])));
    log_line(ANDROID_LOG_INFO, "Flarial parity shim JNI ready");
    return JNI_VERSION_1_6;
}
