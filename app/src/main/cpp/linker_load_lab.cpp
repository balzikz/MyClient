#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <link.h>
#include <limits.h>
#include <sys/stat.h>
#include <unistd.h>

#include <array>
#include <sstream>
#include <string>

namespace {

constexpr char kLogTag[] = "MATH-LINKER";

struct ModuleQuery {
    const std::string* target;
    bool found;
};

struct TestTarget {
    const char* name;
    const char* role;
};

constexpr std::array<TestTarget, 2> kAllowedTargets{{
        {"libfmod.so", "small standalone audio dependency"},
        {"libHttpClient.Android.so", "small Bedrock HTTP dependency"},
}};

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
    if (stat(path.c_str(), &info) != 0 || !S_ISREG(info.st_mode) || access(path.c_str(), R_OK) != 0) {
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

std::string runLinkerLab(const std::string& requestedDirectory) {
    std::ostringstream report;
    report << "MATH BEDROCK LINKER LOAD LAB\n"
           << "Stage: 3.3\n"
           << "Mode: WHITELISTED DLOPEN + IMMEDIATE DLCLOSE\n"
           << "Process policy: SECONDARY APP PROCESS (:linker_lab)\n"
           << "Load flags: RTLD_NOW | RTLD_LOCAL\n"
           << "Exported symbols called: NONE\n"
           << "libminecraftpe.so: BLOCKED BY POLICY\n\n";

    const std::string directory = canonicalPath(requestedDirectory);
    if (directory.empty()) {
        return report.str() + "Directory gate: BLOCK\nLinker verdict: INVALID RUNTIME DIRECTORY";
    }

    const std::string allowedPrefix = directory + "/";
    report << "=== DIRECTORY GATE ===\n"
           << "Runtime directory: " << directory << "\n"
           << "Canonical path: PASS\n\n";

    int passed = 0;
    int failed = 0;

    report << "=== LOAD TESTS ===\n";
    for (const TestTarget& target : kAllowedTargets) {
        const std::string requestedPath = allowedPrefix + target.name;
        const std::string path = canonicalPath(requestedPath);

        report << "[TEST] " << target.name << "\n"
               << "  role=" << target.role << "\n";

        if (path.empty() || path.rfind(allowedPrefix, 0) != 0) {
            report << "  path gate=BLOCK\n"
                   << "  result=FAIL INVALID OR ESCAPED PATH\n";
            ++failed;
            continue;
        }

        long long fileSize = 0;
        if (!isRegularReadableFile(path, &fileSize)) {
            report << "  path=" << path << "\n"
                   << "  file gate=BLOCK\n"
                   << "  result=FAIL FILE NOT READABLE\n";
            ++failed;
            continue;
        }

        report << "  path=" << path << "\n"
               << "  size=" << fileSize << " bytes\n"
               << "  file gate=PASS\n";

        const bool loadedBefore = isModuleLoaded(target.name);
        report << "  loaded before=" << (loadedBefore ? "YES" : "NO") << "\n";
        if (loadedBefore) {
            report << "  result=FAIL PRELOADED MODULE WOULD MAKE TEST AMBIGUOUS\n";
            ++failed;
            continue;
        }

        dlerror();
        void* handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
        const char* loadError = dlerror();

        if (handle == nullptr) {
            report << "  dlopen=FAIL\n"
                   << "  linker error=" << (loadError == nullptr ? "unknown" : loadError) << "\n"
                   << "  result=FAIL\n";
            ++failed;
            continue;
        }

        const bool visibleAfterLoad = isModuleLoaded(target.name);
        report << "  dlopen=PASS\n"
               << "  visible after load=" << (visibleAfterLoad ? "YES" : "NO") << "\n"
               << "  symbols invoked=NO\n";

        dlerror();
        const int closeResult = dlclose(handle);
        const char* closeError = dlerror();
        if (closeResult != 0) {
            report << "  dlclose=FAIL\n"
                   << "  close error=" << (closeError == nullptr ? "unknown" : closeError) << "\n"
                   << "  result=FAIL\n";
            ++failed;
            continue;
        }

        report << "  dlclose=PASS\n"
               << "  loaded after close=" << (isModuleLoaded(target.name) ? "YES" : "NO") << "\n"
               << "  result=PASS\n";
        ++passed;
    }

    report << "\n=== LINKER VERDICT ===\n"
           << "Whitelisted libraries: " << kAllowedTargets.size() << "\n"
           << "Passed: " << passed << "\n"
           << "Failed: " << failed << "\n"
           << "Minecraft library attempted: NO\n"
           << "Linker verdict: "
           << (failed == 0 && passed == static_cast<int>(kAllowedTargets.size())
                       ? "READY FOR STAGE 3.4"
                       : "BLOCKED FOR DIAGNOSIS");

    logInfo("Stage 3.3 linker lab finished: passed=" + std::to_string(passed)
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
