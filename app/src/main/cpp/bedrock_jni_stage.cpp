#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <cstdio>
#include <mutex>
#include <string>

namespace {

constexpr const char* kTag = "MATH-JNI-STAGE";

std::mutex g_mutex;
bool g_attempted = false;
bool g_ready = false;
jint g_version = JNI_ERR;
std::string g_status = "NOT_ATTEMPTED";

std::string from_java_string(JNIEnv* environment, jstring value) {
    if (value == nullptr) return {};
    const char* utf = environment->GetStringUTFChars(value, nullptr);
    if (utf == nullptr) return {};
    std::string result(utf);
    environment->ReleaseStringUTFChars(value, utf);
    return result;
}

std::string version_text(jint version) {
    char buffer[64]{};
    std::snprintf(buffer, sizeof(buffer), "0x%08x (%d)",
                  static_cast<unsigned int>(version), static_cast<int>(version));
    return buffer;
}

std::string snapshot() {
    std::lock_guard<std::mutex> lock(g_mutex);
    return "attempted=" + std::string(g_attempted ? "YES" : "NO")
            + " ready=" + std::string(g_ready ? "YES" : "NO")
            + " version=" + version_text(g_version)
            + " status=" + g_status;
}

void set_failure(const std::string& message) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_ready = false;
    g_status = "FAIL: " + message;
}

jstring result_string(JNIEnv* environment) {
    const std::string result = snapshot();
    return environment->NewStringUTF(result.c_str());
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_balzikz_mathclient_MathShimBridge_nativeInitializeMinecraftJni(
        JNIEnv* environment,
        jclass,
        jstring minecraft_path) {
    const std::string path = from_java_string(environment, minecraft_path);

    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_ready) {
            return environment->NewStringUTF(
                    ("attempted=YES ready=YES version=" + version_text(g_version)
                     + " status=" + g_status).c_str());
        }
        g_attempted = true;
        g_status = "STARTING";
    }

    if (path.empty()) {
        set_failure("empty Minecraft path");
        return result_string(environment);
    }

    JavaVM* virtual_machine = nullptr;
    if (environment->GetJavaVM(&virtual_machine) != JNI_OK || virtual_machine == nullptr) {
        set_failure("GetJavaVM");
        return result_string(environment);
    }

    void* handle = dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (handle == nullptr) {
        const char* raw_error = dlerror();
        set_failure(raw_error == nullptr
                    ? "dlopen failed without dlerror"
                    : raw_error);
        return result_string(environment);
    }

    dlerror();
    void* symbol = dlsym(handle, "JNI_OnLoad");
    const char* raw_symbol_error = dlerror();
    if (symbol == nullptr) {
        set_failure(raw_symbol_error == nullptr
                    ? "JNI_OnLoad missing"
                    : raw_symbol_error);
        dlclose(handle);
        return result_string(environment);
    }

    __android_log_print(ANDROID_LOG_INFO, kTag,
                        "Calling Bedrock JNI_OnLoad at %p", symbol);

    using JniOnLoadFunction = jint (*)(JavaVM*, void*);
    auto function = reinterpret_cast<JniOnLoadFunction>(symbol);
    const jint version = function(virtual_machine, nullptr);

    const bool pending_exception = environment->ExceptionCheck() == JNI_TRUE;
    if (pending_exception) {
        __android_log_print(ANDROID_LOG_ERROR, kTag,
                            "Bedrock JNI_OnLoad returned with a pending Java exception");
        environment->ExceptionDescribe();
        environment->ExceptionClear();
    }

    const bool version_ok = version != JNI_ERR && version != 0;
    const bool ready = version_ok && !pending_exception;

    {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_version = version;
        g_ready = ready;
        if (!version_ok) {
            g_status = "FAIL: invalid JNI version";
        } else if (pending_exception) {
            g_status = "FAIL: pending Java exception cleared";
        } else {
            g_status = "OK: JNI_OnLoad returned successfully";
        }
    }

    dlclose(handle);

    __android_log_print(ready ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, kTag,
                        "Bedrock JNI_OnLoad result version=%s ready=%s",
                        version_text(version).c_str(), ready ? "YES" : "NO");

    return result_string(environment);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_balzikz_mathclient_MathShimBridge_nativeIsMinecraftJniReady(
        JNIEnv*,
        jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_ready ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_balzikz_mathclient_MathShimBridge_nativeMinecraftJniStatus(
        JNIEnv* environment,
        jclass) {
    return result_string(environment);
}
