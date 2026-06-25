#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <link.h>
#include <limits.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstdlib>
#include <sstream>
#include <string>

namespace {

constexpr char kLogTag[] = "MATH-LINKER";
constexpr char kFmodName[] = "libfmod.so";
constexpr char kCppRuntimeName[] = "libc++_shared.so";
constexpr char kHttpClientName[] = "libHttpClient.Android.so";

struct ModuleQuery {
    const std::string* target;
    bool found;
};

std::string baseName(const std::string& path) {
    const std::size_t slash = path.find_last_of('/');
    return slash == std::string::npos ? path : path.substr(slash + 1);
}

int findModule(dl_phdr_info* info, std::size_t, void* data) {
    if (info == nullptr || data == nullptr || info->dlpi_name == nullptr) {
        return 0;
    }

    auto* query = static_cast<ModuleQuery*>(data);
    const std::string path(info->dlpi_name);
    if (!path.empty() && baseName(path) == *query->target) {
        query->found = true;
        return 1;
    }
    return 0;
}

bool isModuleLoaded(const std::string& name) {
    ModuleQuery query{&name, false};
    dl_iterate_phdr(findModule, &query);
    return query.found;
}

std::string canonicalPath(const std::string& path) {
    char resolved[PATH_MAX];
    if (realpath(path.c_str(), resolved) == nullptr) {
        return {};
    }
    return std::string(resolved);
}

bool isRegularReadableFile(const std::string& path, long long* size) {
    struct stat info {};
    if (stat(path.c_str(), &info) != 0
            || !S_ISREG(info.st_mode)
            || access(path.c_str(), R_OK) != 0) {
        return false;
    }
    if (size != nullptr) {
        *size = static_cast<long long>(info.st_size);
    }
    return true;
}

std::string fromJavaString(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char* raw = env->GetStringUTFChars(value, nullptr);
    if (raw == nullptr) {
        return {};
    }
    std::string result(raw);
    env->ReleaseStringUTFChars(value, raw);
    return result;
}

jstring toJavaString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

void logInfo(const std::string& message) {
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "%s", message.c_str());
}

std::string safeDlError() {
    const char* error = dlerror();
    return error == nullptr ? "unknown" : std::string(error);
}

bool validatePath(
        const std::string& allowedPrefix,
        const std::string& name,
        std::string* path,
        long long* size,
        std::ostringstream* report) {
    const std::string candidate = canonicalPath(allowedPrefix + name);
    *report << "  path=" << (candidate.empty() ? "UNRESOLVED" : candidate) << "\n";

    if (candidate.empty() || candidate.rfind(allowedPrefix, 0) != 0) {
        *report << "  path gate=BLOCK\n";
        return false;
    }

    if (!isRegularReadableFile(candidate, size)) {
        *report << "  file gate=BLOCK\n";
        return false;
    }

    *path = candidate;
    *report << "  size=" << *size << " bytes\n"
            << "  path gate=PASS\n"
            << "  file gate=PASS\n";
    return true;
}

bool testStandaloneLibrary(
        const std::string& allowedPrefix,
        const std::string& name,
        const std::string& role,
        std::ostringstream* report) {
    *report << "[TEST] " << name << "\n"
            << "  role=" << role << "\n";

    std::string path;
    long long size = 0;
    if (!validatePath(allowedPrefix, name, &path, &size, report)) {
        *report << "  result=FAIL\n";
        return false;
    }

    const bool loadedBefore = isModuleLoaded(name);
    *report << "  loaded before=" << (loadedBefore ? "YES" : "NO") << "\n";
    if (loadedBefore) {
        *report << "  result=FAIL PRELOADED MODULE WOULD MAKE TEST AMBIGUOUS\n";
        return false;
    }

    dlerror();
    void* handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) {
        *report << "  dlopen=FAIL\n"
                << "  linker error=" << safeDlError() << "\n"
                << "  result=FAIL\n";
        return false;
    }

    *report << "  dlopen=PASS\n"
            << "  visible after load=" << (isModuleLoaded(name) ? "YES" : "NO") << "\n"
            << "  explicit symbols invoked=NO\n";

    dlerror();
    if (dlclose(handle) != 0) {
        *report << "  dlclose=FAIL\n"
                << "  close error=" << safeDlError() << "\n"
                << "  result=FAIL\n";
        return false;
    }

    *report << "  dlclose=PASS\n"
            << "  loaded after close=" << (isModuleLoaded(name) ? "YES" : "NO") << "\n"
            << "  result=PASS\n";
    return true;
}

bool testHttpDependencyChain(
        const std::string& allowedPrefix,
        std::ostringstream* report) {
    *report << "[CHAIN TEST] " << kCppRuntimeName << " -> " << kHttpClientName << "\n"
            << "  role=Bedrock C++ runtime followed by HTTP dependency\n";

    std::string runtimePath;
    std::string clientPath;
    long long runtimeSize = 0;
    long long clientSize = 0;

    *report << "  dependency: " << kCppRuntimeName << "\n";
    if (!validatePath(
            allowedPrefix,
            kCppRuntimeName,
            &runtimePath,
            &runtimeSize,
            report)) {
        *report << "  chain result=FAIL\n";
        return false;
    }

    *report << "  target: " << kHttpClientName << "\n";
    if (!validatePath(
            allowedPrefix,
            kHttpClientName,
            &clientPath,
            &clientSize,
            report)) {
        *report << "  chain result=FAIL\n";
        return false;
    }

    const bool runtimeLoadedBefore = isModuleLoaded(kCppRuntimeName);
    const bool clientLoadedBefore = isModuleLoaded(kHttpClientName);
    *report << "  runtime loaded before=" << (runtimeLoadedBefore ? "YES" : "NO") << "\n"
            << "  client loaded before=" << (clientLoadedBefore ? "YES" : "NO") << "\n";

    if (runtimeLoadedBefore || clientLoadedBefore) {
        *report << "  chain result=FAIL PRELOADED MODULE WOULD MAKE TEST AMBIGUOUS\n";
        return false;
    }

    dlerror();
    void* runtimeHandle = dlopen(runtimePath.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (runtimeHandle == nullptr) {
        *report << "  runtime dlopen=FAIL\n"
                << "  linker error=" << safeDlError() << "\n"
                << "  chain result=FAIL\n";
        return false;
    }

    *report << "  runtime dlopen=PASS\n"
            << "  runtime visibility=GLOBAL\n"
            << "  runtime visible after load="
            << (isModuleLoaded(kCppRuntimeName) ? "YES" : "NO")
            << "\n";

    dlerror();
    void* clientHandle = dlopen(clientPath.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (clientHandle == nullptr) {
        const std::string error = safeDlError();
        *report << "  client dlopen=FAIL\n"
                << "  linker error=" << error << "\n";

        dlerror();
        const int runtimeClose = dlclose(runtimeHandle);
        *report << "  runtime cleanup=" << (runtimeClose == 0 ? "PASS" : "FAIL") << "\n"
                << "  chain result=FAIL\n";
        return false;
    }

    *report << "  client dlopen=PASS\n"
            << "  client visible after load="
            << (isModuleLoaded(kHttpClientName) ? "YES" : "NO")
            << "\n"
            << "  explicit symbols invoked=NO\n";

    bool closePassed = true;

    dlerror();
    if (dlclose(clientHandle) != 0) {
        *report << "  client dlclose=FAIL\n"
                << "  close error=" << safeDlError() << "\n";
        closePassed = false;
    } else {
        *report << "  client dlclose=PASS\n";
    }

    dlerror();
    if (dlclose(runtimeHandle) != 0) {
        *report << "  runtime dlclose=FAIL\n"
                << "  close error=" << safeDlError() << "\n";
        closePassed = false;
    } else {
        *report << "  runtime dlclose=PASS\n";
    }

    *report << "  client loaded after close="
            << (isModuleLoaded(kHttpClientName) ? "YES" : "NO")
            << "\n"
            << "  runtime loaded after close="
            << (isModuleLoaded(kCppRuntimeName) ? "YES" : "NO")
            << "\n"
            << "  chain result=" << (closePassed ? "PASS" : "FAIL") << "\n";
    return closePassed;
}

std::string runLinkerLab(const std::string& requestedDirectory) {
    std::ostringstream report;
    report << "MATH BEDROCK LINKER LOAD LAB\n"
           << "Stage: 3.3.1\n"
           << "Mode: ORDERED DEPENDENCY CHAIN + REVERSE DLCLOSE\n"
           << "Process policy: SECONDARY APP PROCESS (:linker_lab)\n"
           << "Standalone flags: RTLD_NOW | RTLD_LOCAL\n"
           << "Runtime flags: RTLD_NOW | RTLD_GLOBAL\n"
           << "Explicit exported symbols called: NONE\n"
           << "ELF constructors: MAY RUN DURING DLOPEN\n"
           << "libminecraftpe.so: BLOCKED BY POLICY\n\n";

    const std::string directory = canonicalPath(requestedDirectory);
    if (directory.empty()) {
        return report.str()
                + "Directory gate: BLOCK\nLinker verdict: INVALID RUNTIME DIRECTORY";
    }

    const std::string allowedPrefix = directory + "/";
    report << "=== DIRECTORY GATE ===\n"
           << "Runtime directory: " << directory << "\n"
           << "Canonical path: PASS\n\n";

    int passed = 0;
    int failed = 0;

    report << "=== LOAD TESTS ===\n";
    if (testStandaloneLibrary(
            allowedPrefix,
            kFmodName,
            "standalone audio dependency",
            &report)) {
        ++passed;
    } else {
        ++failed;
    }

    if (testHttpDependencyChain(allowedPrefix, &report)) {
        ++passed;
    } else {
        ++failed;
    }

    report << "\n=== LINKER VERDICT ===\n"
           << "Test groups: 2\n"
           << "Passed: " << passed << "\n"
           << "Failed: " << failed << "\n"
           << "Minecraft library attempted: NO\n"
           << "Linker verdict: "
           << (failed == 0 && passed == 2
                       ? "READY FOR STAGE 3.4"
                       : "BLOCKED FOR DIAGNOSIS");

    logInfo("Stage 3.3.1 linker lab finished: passed=" + std::to_string(passed)
            + ", failed=" + std::to_string(failed));
    return report.str();
}

}  // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_com_balzikz_mathclient_NativeBridge_nativeRunLinkerLoadTest(
        JNIEnv* env,
        jclass,
        jstring runtimeDirectory) {
    return toJavaString(env, runLinkerLab(fromJavaString(env, runtimeDirectory)));
}
