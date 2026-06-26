#include <android/log.h>
#include <game-activity/GameActivity.h>
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

extern "C" bool math_bedrock_jni_ready();
extern "C" jint math_bedrock_jni_version();

namespace {

constexpr const char* kTag = "MATH-SHIM";
constexpr const char* kStage = "4.3.0";

std::mutex g_state_mutex;
std::string g_journal_path;
std::string g_minecraft_path;
std::string g_bind_error;
std::string g_forward_error;
void* g_minecraft_handle = nullptr;
void* g_minecraft_game_activity_on_create = nullptr;
void* g_minecraft_jni_on_load = nullptr;
void* g_minecraft_android_main = nullptr;
void* g_minecraft_native_activity_on_create = nullptr;
std::atomic<bool> g_configured{false};
std::atomic<bool> g_bind_attempted{false};
std::atomic<bool> g_minecraft_bound{false};
std::atomic<bool> g_forward_started{false};
std::atomic<bool> g_forward_returned{false};
std::atomic<bool> g_forward_failed{false};
std::atomic<uintptr_t> g_activity_address{0};

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

std::string pointer_hex(const void* value) {
    char buffer[32]{};
    std::snprintf(buffer, sizeof(buffer), "0x%llx",
                  static_cast<unsigned long long>(reinterpret_cast<uintptr_t>(value)));
    return buffer;
}

std::string integer_hex(uintptr_t value) {
    char buffer[32]{};
    std::snprintf(buffer, sizeof(buffer), "0x%llx",
                  static_cast<unsigned long long>(value));
    return buffer;
}

std::string jni_version_text() {
    const jint version = math_bedrock_jni_version();
    char buffer[32]{};
    std::snprintf(buffer, sizeof(buffer), "0x%08x",
                  static_cast<unsigned int>(version));
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
    return "FOUND address=" + pointer_hex(symbol)
            + " base=" + pointer_hex(info.dli_fbase)
            + " offset=" + integer_hex(address - base)
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
        std::lock_guard<std::mutex> lock(g_state_mutex);
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

std::string from_java_string(JNIEnv* environment, jstring value) {
    if (value == nullptr) return {};
    const char* utf = environment->GetStringUTFChars(value, nullptr);
    if (utf == nullptr) return {};
    std::string result(utf);
    environment->ReleaseStringUTFChars(value, utf);
    return result;
}

std::string forward_state() {
    if (g_forward_failed.load()) return "FAILED";
    if (g_forward_returned.load()) return "RETURNED";
    if (g_forward_started.load()) return "IN_PROGRESS";
    return "NOT_STARTED";
}

std::string callback_summary(const GameActivity* activity) {
    if (activity == nullptr) return "activity=NULL";
    if (activity->callbacks == nullptr) return "callbacks=NULL";

    const GameActivityCallbacks* callbacks = activity->callbacks;
    int count = 0;
    count += callbacks->onStart != nullptr;
    count += callbacks->onResume != nullptr;
    count += callbacks->onPause != nullptr;
    count += callbacks->onStop != nullptr;
    count += callbacks->onDestroy != nullptr;
    count += callbacks->onWindowFocusChanged != nullptr;
    count += callbacks->onNativeWindowCreated != nullptr;
    count += callbacks->onNativeWindowResized != nullptr;
    count += callbacks->onNativeWindowRedrawNeeded != nullptr;
    count += callbacks->onNativeWindowDestroyed != nullptr;
    count += callbacks->onTouchEvent != nullptr;
    count += callbacks->onKeyDown != nullptr;
    count += callbacks->onKeyUp != nullptr;
    count += callbacks->onTextInputEvent != nullptr;

    return "callbacks_non_null=" + std::to_string(count)
            + " start=" + (callbacks->onStart == nullptr ? "NO" : "YES")
            + " resume=" + (callbacks->onResume == nullptr ? "NO" : "YES")
            + " window=" + (callbacks->onNativeWindowCreated == nullptr ? "NO" : "YES")
            + " touch=" + (callbacks->onTouchEvent == nullptr ? "NO" : "YES");
}

std::string status_text() {
    std::string minecraft_path;
    std::string bind_error;
    std::string forward_error;
    void* handle = nullptr;
    void* game_activity_on_create = nullptr;
    void* jni_on_load = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_state_mutex);
        minecraft_path = g_minecraft_path;
        bind_error = g_bind_error;
        forward_error = g_forward_error;
        handle = g_minecraft_handle;
        game_activity_on_create = g_minecraft_game_activity_on_create;
        jni_on_load = g_minecraft_jni_on_load;
    }

    return std::string("stage=") + kStage
            + " configured=" + (g_configured.load() ? "YES" : "NO")
            + " bind=" + (g_minecraft_bound.load() ? "READY" : "NOT_READY")
            + " handle=" + (handle == nullptr ? "MISSING" : "READY")
            + " GameActivity_onCreate="
            + (game_activity_on_create == nullptr ? "MISSING" : "FOUND")
            + " JNI_OnLoad="
            + (jni_on_load == nullptr
               ? "MISSING"
               : (math_bedrock_jni_ready() ? "CALLED_READY" : "FOUND_NOT_READY"))
            + " jni_version=" + jni_version_text()
            + " forwarding=" + forward_state()
            + " activity=" + integer_hex(g_activity_address.load())
            + " minecraft_path=" + (minecraft_path.empty() ? "MISSING" : "CONFIGURED")
            + " bedrock_loaded=" + (g_minecraft_bound.load() ? "YES" : "NO")
            + " bedrock_started=" + (g_forward_returned.load() ? "YES" : "NO")
            + (bind_error.empty() ? "" : " bind_error=" + clean(bind_error))
            + (forward_error.empty() ? "" : " forward_error=" + clean(forward_error));
}

std::string bind_minecraft() {
    if (g_minecraft_bound.load()) return status_text();

    std::string path;
    {
        std::lock_guard<std::mutex> lock(g_state_mutex);
        path = g_minecraft_path;
    }

    g_bind_attempted.store(true);
    append_event("BEDROCK_BIND_START",
                 "path=" + (path.empty() ? std::string("MISSING") : path)
                         + " mode=RTLD_NOW|RTLD_GLOBAL forwarding=ARMED");

    if (path.empty()) {
        const std::string error = "Minecraft path is empty";
        {
            std::lock_guard<std::mutex> lock(g_state_mutex);
            g_bind_error = error;
        }
        append_event("BEDROCK_BIND_FAIL", error);
        return status_text();
    }

    dlerror();
    void* handle = dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (handle == nullptr) {
        const char* raw_error = dlerror();
        const std::string error = raw_error == nullptr
                ? "dlopen failed without dlerror"
                : raw_error;
        {
            std::lock_guard<std::mutex> lock(g_state_mutex);
            g_bind_error = error;
        }
        append_event("BEDROCK_BIND_FAIL", error);
        return status_text();
    }

    dlerror();
    void* game_activity_on_create = dlsym(handle, "GameActivity_onCreate");
    const char* raw_game_error = dlerror();
    const std::string game_symbol_error = raw_game_error == nullptr ? "" : raw_game_error;

    dlerror();
    void* jni_on_load = dlsym(handle, "JNI_OnLoad");
    const char* raw_jni_error = dlerror();
    const std::string jni_symbol_error = raw_jni_error == nullptr ? "" : raw_jni_error;

    dlerror();
    void* android_main_symbol = dlsym(handle, "android_main");
    dlerror();

    dlerror();
    void* native_activity_on_create = dlsym(handle, "ANativeActivity_onCreate");
    dlerror();

    append_event("BEDROCK_SYMBOL_GAME_ACTIVITY_ON_CREATE",
                 symbol_description(game_activity_on_create));
    append_event("BEDROCK_SYMBOL_JNI_ON_LOAD", symbol_description(jni_on_load));
    append_event("BEDROCK_SYMBOL_ANDROID_MAIN_OPTIONAL",
                 symbol_description(android_main_symbol));
    append_event("BEDROCK_SYMBOL_NATIVE_ACTIVITY_ON_CREATE_OPTIONAL",
                 symbol_description(native_activity_on_create));

    if (game_activity_on_create == nullptr || jni_on_load == nullptr) {
        std::string error = "required symbols missing";
        if (!game_symbol_error.empty()) error += " GameActivity_onCreate=" + game_symbol_error;
        if (!jni_symbol_error.empty()) error += " JNI_OnLoad=" + jni_symbol_error;
        dlclose(handle);
        {
            std::lock_guard<std::mutex> lock(g_state_mutex);
            g_bind_error = error;
        }
        append_event("BEDROCK_BIND_FAIL", error);
        return status_text();
    }

    {
        std::lock_guard<std::mutex> lock(g_state_mutex);
        g_minecraft_handle = handle;
        g_minecraft_game_activity_on_create = game_activity_on_create;
        g_minecraft_jni_on_load = jni_on_load;
        g_minecraft_android_main = android_main_symbol;
        g_minecraft_native_activity_on_create = native_activity_on_create;
        g_bind_error.clear();
    }
    g_minecraft_bound.store(true);

    append_event("BEDROCK_BIND_OK",
                 "handle=" + pointer_hex(handle)
                         + " required=2/2 forwarding=ARMED bedrock_started=NO");
    return status_text();
}

void set_forward_failure(const std::string& error) {
    {
        std::lock_guard<std::mutex> lock(g_state_mutex);
        g_forward_error = error;
    }
    g_forward_failed.store(true);
    append_event("BEDROCK_GAMEACTIVITY_FORWARD_FAIL", error);
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_balzikz_mathclient_MathShimBridge_nativeConfigure(
        JNIEnv* environment,
        jclass,
        jstring journal_path,
        jstring minecraft_path) {
    const std::string journal = from_java_string(environment, journal_path);
    const std::string minecraft = from_java_string(environment, minecraft_path);

    {
        std::lock_guard<std::mutex> lock(g_state_mutex);
        g_journal_path = journal;
        g_minecraft_path = minecraft;
        g_bind_error.clear();
        g_forward_error.clear();
    }
    g_configured.store(!journal.empty() && !minecraft.empty());

    append_event("SHIM_CONFIGURED",
                 std::string("stage=") + kStage
                         + " journal=" + (journal.empty() ? "MISSING" : "READY")
                         + " minecraft=" + (minecraft.empty() ? "MISSING" : "READY")
                         + " forwarding=NOT_STARTED");

    const std::string result = status_text();
    return environment->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_balzikz_mathclient_MathShimBridge_nativeBindMinecraft(
        JNIEnv* environment,
        jclass) {
    const std::string result = bind_minecraft();
    return environment->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_balzikz_mathclient_MathShimBridge_nativeIsMinecraftBound(JNIEnv*, jclass) {
    return g_minecraft_bound.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_balzikz_mathclient_MathShimBridge_nativeIsBedrockForwarded(JNIEnv*, jclass) {
    return g_forward_returned.load() && !g_forward_failed.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_balzikz_mathclient_MathShimBridge_nativeStatus(JNIEnv* environment, jclass) {
    const std::string result = status_text();
    return environment->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void GameActivity_onCreate(
        GameActivity* activity,
        void* saved_state,
        size_t saved_state_size) {
    g_forward_started.store(true);
    g_activity_address.store(reinterpret_cast<uintptr_t>(activity));

    void* target = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_state_mutex);
        target = g_minecraft_game_activity_on_create;
    }

    append_event("BEDROCK_GAMEACTIVITY_FORWARD_START",
                 "activity=" + pointer_hex(activity)
                         + " saved_state_bytes=" + std::to_string(saved_state_size)
                         + " target=" + pointer_hex(target)
                         + " jni_ready=" + (math_bedrock_jni_ready() ? "YES" : "NO")
                         + " instance_before="
                         + pointer_hex(activity == nullptr ? nullptr : activity->instance));

    if (activity == nullptr) {
        set_forward_failure("GameActivity pointer is null");
        return;
    }
    if (!g_minecraft_bound.load() || target == nullptr) {
        set_forward_failure("Bedrock GameActivity_onCreate is not bound");
        return;
    }
    if (!math_bedrock_jni_ready()) {
        set_forward_failure("Bedrock JNI_OnLoad is not ready");
        return;
    }

    using BedrockCreateFunction = void (*)(GameActivity*, void*, size_t);
    auto bedrock_create = reinterpret_cast<BedrockCreateFunction>(target);

    bedrock_create(activity, saved_state, saved_state_size);

    const bool has_instance = activity->instance != nullptr;
    const bool has_callbacks = activity->callbacks != nullptr
            && (activity->callbacks->onStart != nullptr
                || activity->callbacks->onResume != nullptr
                || activity->callbacks->onNativeWindowCreated != nullptr);

    if (!has_instance && !has_callbacks) {
        set_forward_failure("Bedrock returned without instance or core callbacks");
        return;
    }

    g_forward_returned.store(true);
    append_event("BEDROCK_GAMEACTIVITY_FORWARD_RETURN",
                 "activity=" + pointer_hex(activity)
                         + " instance_after=" + pointer_hex(activity->instance)
                         + " " + callback_summary(activity)
                         + " bedrock_started=YES");
}
