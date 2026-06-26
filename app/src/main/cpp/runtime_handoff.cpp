#include <android/log.h>
#include <android/native_activity.h>
#include <game-activity/native_app_glue/android_native_app_glue.h>
#include <jni.h>

#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <dlfcn.h>
#include <fcntl.h>
#include <mutex>
#include <string>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

extern "C" void android_main(android_app* app);
extern "C" void ANativeActivity_onCreate(
        ANativeActivity* activity,
        void* saved_state,
        size_t saved_state_size);

namespace {

constexpr const char* kTag = "MATH-HANDOFF";
constexpr const char* kStage = "5.1.0";

using AndroidMainFunction = void (*)(android_app*);
using NativeActivityCreateFunction = void (*)(ANativeActivity*, void*, size_t);

std::mutex g_mutex;
std::string g_journal_path;
std::string g_loaded_path;
std::string g_error;
void* g_handle = nullptr;
AndroidMainFunction g_target_android_main = nullptr;
NativeActivityCreateFunction g_target_native_create = nullptr;
void* g_game_activity_on_create = nullptr;
void* g_jni_on_load = nullptr;
std::atomic<bool> g_connect_attempted{false};
std::atomic<bool> g_connected{false};
std::atomic<bool> g_android_main_entered{false};
std::atomic<bool> g_native_create_entered{false};
std::atomic<bool> g_bedrock_started{false};

long long now_millis() {
    timespec value{};
    clock_gettime(CLOCK_REALTIME, &value);
    return static_cast<long long>(value.tv_sec) * 1000LL
            + static_cast<long long>(value.tv_nsec / 1000000L);
}

long current_tid() {
    return static_cast<long>(syscall(SYS_gettid));
}

std::string clean(std::string value) {
    for (char& character : value) {
        if (character == '\n' || character == '\r') character = ' ';
    }
    if (value.size() > 4096U) {
        value.resize(4096U);
        value += "...";
    }
    return value;
}

std::string from_java_string(JNIEnv* environment, jstring value) {
    if (value == nullptr) return {};
    const char* utf = environment->GetStringUTFChars(value, nullptr);
    if (utf == nullptr) return {};
    std::string result(utf);
    environment->ReleaseStringUTFChars(value, utf);
    return result;
}

std::string pointer_hex(const void* value) {
    char buffer[32]{};
    std::snprintf(buffer, sizeof(buffer), "0x%llx",
                  static_cast<unsigned long long>(reinterpret_cast<uintptr_t>(value)));
    return buffer;
}

std::string symbol_description(void* symbol) {
    if (symbol == nullptr) return "MISSING";
    Dl_info info{};
    if (dladdr(symbol, &info) == 0 || info.dli_fbase == nullptr) {
        return "FOUND address=" + pointer_hex(symbol) + " module=UNKNOWN";
    }
    const auto address = reinterpret_cast<uintptr_t>(symbol);
    const auto base = reinterpret_cast<uintptr_t>(info.dli_fbase);
    char offset[32]{};
    std::snprintf(offset, sizeof(offset), "0x%llx",
                  static_cast<unsigned long long>(address - base));
    return "FOUND address=" + pointer_hex(symbol)
            + " base=" + pointer_hex(info.dli_fbase)
            + " offset=" + offset
            + " module=" + (info.dli_fname == nullptr ? "UNKNOWN" : info.dli_fname);
}

void write_all(int descriptor, const char* data, size_t size) {
    while (size > 0U) {
        const ssize_t written = write(descriptor, data, size);
        if (written > 0) {
            data += written;
            size -= static_cast<size_t>(written);
            continue;
        }
        if (written < 0 && errno == EINTR) continue;
        break;
    }
}

void append_event(const char* status, const std::string& detail) {
    const std::string safe_status = clean(status == nullptr ? "NONE" : status);
    const std::string safe_detail = clean(detail);
    __android_log_print(ANDROID_LOG_INFO, kTag, "%s | %s",
                        safe_status.c_str(), safe_detail.c_str());

    std::string path;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        path = g_journal_path;
    }
    if (path.empty()) return;

    const std::string record = "\n---\nstatus=" + safe_status
            + "\ndetail=" + safe_detail
            + "\npid=" + std::to_string(getpid())
            + "\ntid=" + std::to_string(current_tid())
            + "\ntime=" + std::to_string(now_millis()) + "\n";
    const int descriptor = open(path.c_str(), O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
    if (descriptor < 0) return;
    write_all(descriptor, record.data(), record.size());
    fsync(descriptor);
    close(descriptor);
}

void set_failure(const std::string& message) {
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_error = message;
    }
    g_connected.store(false);
    append_event("BEDROCK_CONNECT_FAIL", message);
}

std::string status_text() {
    std::string path;
    std::string error;
    void* handle = nullptr;
    AndroidMainFunction target_main = nullptr;
    NativeActivityCreateFunction target_create = nullptr;
    void* game_create = nullptr;
    void* jni_load = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        path = g_loaded_path;
        error = g_error;
        handle = g_handle;
        target_main = g_target_android_main;
        target_create = g_target_native_create;
        game_create = g_game_activity_on_create;
        jni_load = g_jni_on_load;
    }

    return std::string("stage=") + kStage
            + " connect_attempted=" + (g_connect_attempted.load() ? "YES" : "NO")
            + " connected=" + (g_connected.load() ? "YES" : "NO")
            + " handle=" + (handle == nullptr ? "MISSING" : "READY")
            + " target_android_main=" + (target_main == nullptr ? "MISSING" : "FOUND")
            + " target_ANativeActivity_onCreate="
            + (target_create == nullptr ? "MISSING" : "FOUND")
            + " GameActivity_onCreate=" + (game_create == nullptr ? "MISSING" : "FOUND_UNUSED")
            + " JNI_OnLoad=" + (jni_load == nullptr ? "MISSING" : "FOUND_VM_OWNED")
            + " android_main_entered=" + (g_android_main_entered.load() ? "YES" : "NO")
            + " native_create_entered=" + (g_native_create_entered.load() ? "YES" : "NO")
            + " bedrock_started=" + (g_bedrock_started.load() ? "YES" : "NO")
            + " path=" + (path.empty() ? "MISSING" : "CONFIGURED")
            + (error.empty() ? "" : " error=" + clean(error));
}

std::string connect_loaded_runtime(const std::string& journal, const std::string& path) {
    if (g_connected.load()) return status_text();

    {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_journal_path = journal;
        g_loaded_path = path;
        g_error.clear();
    }

    g_connect_attempted.store(true);
    append_event("BEDROCK_CONNECT_START",
                 "mode=RTLD_NOLOAD|RTLD_NOW|RTLD_GLOBAL"
                         " jni_onload_call=NO"
                         " path=" + (path.empty() ? std::string("MISSING") : path));

    if (journal.empty() || path.empty()) {
        set_failure("journal path or exact JVM-loaded path is empty");
        return status_text();
    }

    dlerror();
    void* handle = dlopen(path.c_str(), RTLD_NOLOAD | RTLD_NOW | RTLD_GLOBAL);
    if (handle == nullptr) {
        const char* raw_error = dlerror();
        set_failure(raw_error == nullptr
                    ? "RTLD_NOLOAD failed without dlerror"
                    : raw_error);
        return status_text();
    }

    append_event("BEDROCK_CONNECT_NOLOAD_OK", "handle=" + pointer_hex(handle));

    dlerror();
    void* target_main = dlsym(handle, "android_main");
    const char* main_error_raw = dlerror();
    const std::string main_error = main_error_raw == nullptr ? "" : main_error_raw;

    dlerror();
    void* target_create = dlsym(handle, "ANativeActivity_onCreate");
    const char* create_error_raw = dlerror();
    const std::string create_error = create_error_raw == nullptr ? "" : create_error_raw;

    dlerror();
    void* game_create = dlsym(handle, "GameActivity_onCreate");
    dlerror();

    dlerror();
    void* jni_load = dlsym(handle, "JNI_OnLoad");
    dlerror();

    append_event("BEDROCK_SYMBOL_ANDROID_MAIN", symbol_description(target_main));
    append_event("BEDROCK_SYMBOL_NATIVE_ACTIVITY_ON_CREATE", symbol_description(target_create));
    append_event("BEDROCK_SYMBOL_GAME_ACTIVITY_ON_CREATE_UNUSED", symbol_description(game_create));
    append_event("BEDROCK_SYMBOL_JNI_ON_LOAD_VM_OWNED", symbol_description(jni_load));

    if (target_main == nullptr || target_create == nullptr) {
        std::string message = "required Bedrock entrypoint missing";
        if (!main_error.empty()) message += " android_main=" + main_error;
        if (!create_error.empty()) message += " ANativeActivity_onCreate=" + create_error;
        dlclose(handle);
        set_failure(message);
        return status_text();
    }

    if (target_main == reinterpret_cast<void*>(&android_main)
            || target_create == reinterpret_cast<void*>(&ANativeActivity_onCreate)) {
        dlclose(handle);
        set_failure("entrypoint resolution returned MATH proxy recursively");
        return status_text();
    }

    {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_handle = handle;
        g_target_android_main = reinterpret_cast<AndroidMainFunction>(target_main);
        g_target_native_create = reinterpret_cast<NativeActivityCreateFunction>(target_create);
        g_game_activity_on_create = game_create;
        g_jni_on_load = jni_load;
        g_error.clear();
    }
    g_connected.store(true);
    append_event("BEDROCK_CONNECT_READY",
                 "owner=SINGLE_NATIVE_APP_GLUE forwarding=ARMED jni_onload_call=NO");
    return status_text();
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_balzikz_mathclient_MathShimBridge_nativeConnectLoadedRuntime(
        JNIEnv* environment,
        jclass,
        jstring journal_path,
        jstring loaded_path) {
    const std::string result = connect_loaded_runtime(
            from_java_string(environment, journal_path),
            from_java_string(environment, loaded_path));
    return environment->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_balzikz_mathclient_MathShimBridge_nativeIsRuntimeConnected(JNIEnv*, jclass) {
    return g_connected.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_balzikz_mathclient_MathShimBridge_nativeHandoffStatus(
        JNIEnv* environment,
        jclass) {
    const std::string result = status_text();
    return environment->NewStringUTF(result.c_str());
}

extern "C" void ANativeActivity_onCreate(
        ANativeActivity* activity,
        void* saved_state,
        size_t saved_state_size) {
    g_native_create_entered.store(true);
    NativeActivityCreateFunction target = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        target = g_target_native_create;
    }

    append_event("BEDROCK_NATIVE_ACTIVITY_FORWARD_START",
                 "activity=" + pointer_hex(activity)
                         + " saved_state_bytes=" + std::to_string(saved_state_size)
                         + " target=" + pointer_hex(reinterpret_cast<void*>(target))
                         + " connected=" + (g_connected.load() ? "YES" : "NO"));

    if (activity == nullptr || !g_connected.load() || target == nullptr) {
        append_event("BEDROCK_NATIVE_ACTIVITY_FORWARD_FAIL",
                     activity == nullptr ? "activity=NULL" : "target not ready");
        g_native_create_entered.store(false);
        return;
    }

    target(activity, saved_state, saved_state_size);
    append_event("BEDROCK_NATIVE_ACTIVITY_FORWARD_RETURN", "target returned");
    g_native_create_entered.store(false);
}

extern "C" void android_main(android_app* app) {
    g_android_main_entered.store(true);
    AndroidMainFunction target = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        target = g_target_android_main;
    }

    append_event("BEDROCK_ANDROID_MAIN_FORWARD_START",
                 "app=" + pointer_hex(app)
                         + " target=" + pointer_hex(reinterpret_cast<void*>(target))
                         + " connected=" + (g_connected.load() ? "YES" : "NO")
                         + " owner=SINGLE_NATIVE_APP_GLUE");

    if (app == nullptr || !g_connected.load() || target == nullptr) {
        append_event("BEDROCK_ANDROID_MAIN_FORWARD_FAIL",
                     app == nullptr ? "app=NULL" : "target not ready");
        g_android_main_entered.store(false);
        return;
    }

    g_bedrock_started.store(true);
    target(app);
    append_event("BEDROCK_ANDROID_MAIN_FORWARD_RETURN",
                 "destroyRequested=" + std::to_string(app->destroyRequested));
    g_android_main_entered.store(false);
}
