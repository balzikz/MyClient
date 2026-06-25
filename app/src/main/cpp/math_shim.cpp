#include <android/log.h>
#include <android/looper.h>
#include <game-activity/native_app_glue/android_native_app_glue.h>
#include <jni.h>

#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <mutex>
#include <string>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

namespace {

constexpr const char* kTag = "MATH-SHIM";
constexpr const char* kStage = "4.0.0";

std::mutex g_state_mutex;
std::string g_journal_path;
std::string g_minecraft_path;
std::atomic<bool> g_configured{false};
std::atomic<bool> g_android_main_entered{false};
std::atomic<int32_t> g_last_app_command{-1};

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

void on_app_command(android_app*, int32_t command) {
    g_last_app_command.store(command, std::memory_order_relaxed);
    append_event("SHIM_APP_COMMAND", "command=" + std::to_string(command));
}

std::string status_text() {
    std::string minecraft_path;
    {
        std::lock_guard<std::mutex> lock(g_state_mutex);
        minecraft_path = g_minecraft_path;
    }

    return std::string("stage=") + kStage
            + " configured=" + (g_configured.load() ? "YES" : "NO")
            + " android_main=" + (g_android_main_entered.load() ? "ENTERED" : "NOT_ENTERED")
            + " last_command=" + std::to_string(g_last_app_command.load())
            + " minecraft_path=" + (minecraft_path.empty() ? "MISSING" : "CONFIGURED")
            + " bedrock_loaded=NO";
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
    }
    g_configured.store(!journal.empty() && !minecraft.empty());

    append_event("SHIM_CONFIGURED",
                 std::string("stage=") + kStage
                         + " journal=" + (journal.empty() ? "MISSING" : "READY")
                         + " minecraft=" + (minecraft.empty() ? "MISSING" : "READY")
                         + " bedrock_loaded=NO");

    const std::string result = status_text();
    return environment->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_balzikz_mathclient_MathShimBridge_nativeStatus(JNIEnv* environment, jclass) {
    const std::string result = status_text();
    return environment->NewStringUTF(result.c_str());
}

extern "C" void android_main(android_app* app) {
    g_android_main_entered.store(true);
    append_event("SHIM_ANDROID_MAIN_ENTER",
                 app == nullptr ? "app=NULL" : "app=READY bedrock_forwarding=DISABLED");

    if (app == nullptr) {
        append_event("SHIM_ANDROID_MAIN_EXIT", "reason=NULL_APP");
        return;
    }

    app->onAppCmd = on_app_command;

    while (app->destroyRequested == 0) {
        int events = 0;
        android_poll_source* source = nullptr;
        const int result = ALooper_pollOnce(
                -1,
                nullptr,
                &events,
                reinterpret_cast<void**>(&source));

        if (result == ALOOPER_POLL_ERROR) {
            append_event("SHIM_LOOP_ERROR", "ALooper_pollOnce returned error");
            break;
        }
        if (source != nullptr) source->process(app, source);
    }

    append_event("SHIM_ANDROID_MAIN_EXIT", "destroyRequested=YES");
    g_android_main_entered.store(false);
}
