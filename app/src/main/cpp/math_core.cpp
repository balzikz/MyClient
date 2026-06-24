#include <jni.h>
#include <android/log.h>
#include <link.h>

#include <algorithm>
#include <set>
#include <sstream>
#include <string>
#include <vector>

namespace {

constexpr char kLogTag[] = "MATH-NATIVE";
constexpr char kCoreVersion[] = "0.6.0-alpha";

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

std::string baseName(const std::string& path) {
    const std::size_t slash = path.find_last_of('/');
    return slash == std::string::npos ? path : path.substr(slash + 1);
}

int collectModule(dl_phdr_info* info, std::size_t, void* data) {
    if (info == nullptr || data == nullptr || info->dlpi_name == nullptr) {
        return 0;
    }

    const std::string path(info->dlpi_name);
    if (path.empty()) {
        return 0;
    }

    auto* modules = static_cast<std::set<std::string>*>(data);
    modules->insert(baseName(path));
    return 0;
}

std::string scanLoadedModules() {
    std::set<std::string> uniqueModules;
    dl_iterate_phdr(collectModule, &uniqueModules);

    std::vector<std::string> modules(uniqueModules.begin(), uniqueModules.end());
    std::sort(modules.begin(), modules.end());

    std::ostringstream report;
    report << "Current process modules: " << modules.size() << "\n";
    for (std::size_t index = 0; index < modules.size(); ++index) {
        report << index + 1 << ". " << modules[index];
        if (index + 1 < modules.size()) {
            report << "\n";
        }
    }

    logInfo("ModuleScanner returned all " + std::to_string(modules.size()) + " loaded objects");
    return report.str();
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
         << "ModuleScanner: READY\n"
         << "Logcat tag: " << kLogTag;

    logInfo("Native core status requested through JNI");
    return toJavaString(env, info.str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_balzikz_mathclient_NativeBridge_nativeGetLoadedModules(JNIEnv* env, jclass) {
    return toJavaString(env, scanLoadedModules());
}
