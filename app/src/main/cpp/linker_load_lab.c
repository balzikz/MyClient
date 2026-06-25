#define _GNU_SOURCE

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <link.h>
#include <limits.h>
#include <sys/stat.h>
#include <unistd.h>

#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define REPORT_CAPACITY (32 * 1024)

static const char* LOG_TAG = "MATH-C-LINKER";
static const char* FMOD_NAME = "libfmod.so";
static const char* CPP_RUNTIME_NAME = "libc++_shared.so";
static const char* HTTP_CLIENT_NAME = "libHttpClient.Android.so";

typedef struct {
    const char* target;
    bool found;
    char path[PATH_MAX];
} ModuleQuery;

static void appendf(char* report, size_t capacity, const char* format, ...) {
    size_t used = strnlen(report, capacity);
    if (used >= capacity - 1) {
        return;
    }

    va_list args;
    va_start(args, format);
    vsnprintf(report + used, capacity - used, format, args);
    va_end(args);
}

static const char* base_name(const char* path) {
    if (path == NULL) {
        return "";
    }
    const char* slash = strrchr(path, '/');
    return slash == NULL ? path : slash + 1;
}

static int find_module_callback(struct dl_phdr_info* info, size_t size, void* data) {
    (void) size;
    if (info == NULL || data == NULL || info->dlpi_name == NULL) {
        return 0;
    }

    ModuleQuery* query = (ModuleQuery*) data;
    if (info->dlpi_name[0] != '\0'
            && strcmp(base_name(info->dlpi_name), query->target) == 0) {
        query->found = true;
        snprintf(query->path, sizeof(query->path), "%s", info->dlpi_name);
        return 1;
    }
    return 0;
}

static bool find_loaded_module(const char* name, char* path, size_t path_capacity) {
    ModuleQuery query;
    memset(&query, 0, sizeof(query));
    query.target = name;
    dl_iterate_phdr(find_module_callback, &query);

    if (query.found && path != NULL && path_capacity > 0) {
        snprintf(path, path_capacity, "%s", query.path);
    }
    return query.found;
}

static bool canonical_path(const char* input, char* output, size_t output_capacity) {
    if (input == NULL || output == NULL || output_capacity < PATH_MAX) {
        return false;
    }
    return realpath(input, output) != NULL;
}

static bool regular_readable_file(const char* path, long long* size) {
    struct stat info;
    memset(&info, 0, sizeof(info));
    if (stat(path, &info) != 0
            || !S_ISREG(info.st_mode)
            || access(path, R_OK) != 0) {
        return false;
    }
    if (size != NULL) {
        *size = (long long) info.st_size;
    }
    return true;
}

static bool validate_library_path(
        const char* directory,
        const char* name,
        char* resolved,
        long long* size,
        char* report) {
    char candidate[PATH_MAX];
    snprintf(candidate, sizeof(candidate), "%s/%s", directory, name);

    if (!canonical_path(candidate, resolved, PATH_MAX)) {
        appendf(report, REPORT_CAPACITY,
                "  path=UNRESOLVED\n"
                "  path gate=BLOCK\n");
        return false;
    }

    size_t directory_length = strlen(directory);
    bool inside_directory = strncmp(resolved, directory, directory_length) == 0
            && resolved[directory_length] == '/';

    appendf(report, REPORT_CAPACITY, "  path=%s\n", resolved);
    if (!inside_directory) {
        appendf(report, REPORT_CAPACITY, "  path gate=BLOCK ESCAPED DIRECTORY\n");
        return false;
    }

    if (!regular_readable_file(resolved, size)) {
        appendf(report, REPORT_CAPACITY, "  file gate=BLOCK\n");
        return false;
    }

    appendf(report, REPORT_CAPACITY,
            "  size=%lld bytes\n"
            "  path gate=PASS\n"
            "  file gate=PASS\n",
            *size);
    return true;
}

static const char* current_dl_error(void) {
    const char* error = dlerror();
    return error == NULL ? "unknown" : error;
}

static bool test_standalone_library(const char* directory, char* report) {
    appendf(report, REPORT_CAPACITY,
            "[TEST] %s\n"
            "  role=standalone audio dependency\n",
            FMOD_NAME);

    char path[PATH_MAX];
    long long size = 0;
    if (!validate_library_path(directory, FMOD_NAME, path, &size, report)) {
        appendf(report, REPORT_CAPACITY, "  result=FAIL\n");
        return false;
    }

    char loaded_path[PATH_MAX] = {0};
    bool loaded_before = find_loaded_module(FMOD_NAME, loaded_path, sizeof(loaded_path));
    appendf(report, REPORT_CAPACITY,
            "  loaded before=%s\n",
            loaded_before ? "YES" : "NO");
    if (loaded_before) {
        appendf(report, REPORT_CAPACITY,
                "  loaded path=%s\n"
                "  result=FAIL PRELOADED MODULE\n",
                loaded_path);
        return false;
    }

    dlerror();
    void* handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (handle == NULL) {
        appendf(report, REPORT_CAPACITY,
                "  dlopen=FAIL\n"
                "  linker error=%s\n"
                "  result=FAIL\n",
                current_dl_error());
        return false;
    }

    appendf(report, REPORT_CAPACITY,
            "  dlopen=PASS\n"
            "  visible after load=%s\n"
            "  explicit symbols invoked=NO\n",
            find_loaded_module(FMOD_NAME, loaded_path, sizeof(loaded_path)) ? "YES" : "NO");

    dlerror();
    if (dlclose(handle) != 0) {
        appendf(report, REPORT_CAPACITY,
                "  dlclose=FAIL\n"
                "  close error=%s\n"
                "  result=FAIL\n",
                current_dl_error());
        return false;
    }

    appendf(report, REPORT_CAPACITY,
            "  dlclose=PASS\n"
            "  loaded after close=%s\n"
            "  result=PASS\n",
            find_loaded_module(FMOD_NAME, loaded_path, sizeof(loaded_path)) ? "YES" : "NO");
    return true;
}

static bool test_http_chain(const char* directory, char* report) {
    appendf(report, REPORT_CAPACITY,
            "[CHAIN TEST] %s -> %s\n"
            "  role=Bedrock C++ runtime followed by HTTP dependency\n",
            CPP_RUNTIME_NAME,
            HTTP_CLIENT_NAME);

    char runtime_path[PATH_MAX];
    char client_path[PATH_MAX];
    long long runtime_size = 0;
    long long client_size = 0;

    appendf(report, REPORT_CAPACITY, "  dependency: %s\n", CPP_RUNTIME_NAME);
    if (!validate_library_path(
            directory,
            CPP_RUNTIME_NAME,
            runtime_path,
            &runtime_size,
            report)) {
        appendf(report, REPORT_CAPACITY, "  chain result=FAIL\n");
        return false;
    }

    appendf(report, REPORT_CAPACITY, "  target: %s\n", HTTP_CLIENT_NAME);
    if (!validate_library_path(
            directory,
            HTTP_CLIENT_NAME,
            client_path,
            &client_size,
            report)) {
        appendf(report, REPORT_CAPACITY, "  chain result=FAIL\n");
        return false;
    }

    char preloaded_runtime_path[PATH_MAX] = {0};
    char preloaded_client_path[PATH_MAX] = {0};
    bool runtime_loaded_before = find_loaded_module(
            CPP_RUNTIME_NAME,
            preloaded_runtime_path,
            sizeof(preloaded_runtime_path));
    bool client_loaded_before = find_loaded_module(
            HTTP_CLIENT_NAME,
            preloaded_client_path,
            sizeof(preloaded_client_path));

    appendf(report, REPORT_CAPACITY,
            "  runtime loaded before=%s\n"
            "  client loaded before=%s\n",
            runtime_loaded_before ? "YES" : "NO",
            client_loaded_before ? "YES" : "NO");

    if (runtime_loaded_before) {
        appendf(report, REPORT_CAPACITY,
                "  preloaded runtime path=%s\n",
                preloaded_runtime_path);
    }
    if (client_loaded_before) {
        appendf(report, REPORT_CAPACITY,
                "  preloaded client path=%s\n",
                preloaded_client_path);
    }
    if (runtime_loaded_before || client_loaded_before) {
        appendf(report, REPORT_CAPACITY,
                "  chain result=FAIL PRELOADED MODULE\n");
        return false;
    }

    dlerror();
    void* runtime_handle = dlopen(runtime_path, RTLD_NOW | RTLD_GLOBAL);
    if (runtime_handle == NULL) {
        appendf(report, REPORT_CAPACITY,
                "  runtime dlopen=FAIL\n"
                "  linker error=%s\n"
                "  chain result=FAIL\n",
                current_dl_error());
        return false;
    }

    char loaded_path[PATH_MAX] = {0};
    appendf(report, REPORT_CAPACITY,
            "  runtime dlopen=PASS\n"
            "  runtime visibility=GLOBAL\n"
            "  runtime visible after load=%s\n",
            find_loaded_module(CPP_RUNTIME_NAME, loaded_path, sizeof(loaded_path))
                    ? "YES" : "NO");

    dlerror();
    void* client_handle = dlopen(client_path, RTLD_NOW | RTLD_LOCAL);
    if (client_handle == NULL) {
        const char* error = current_dl_error();
        appendf(report, REPORT_CAPACITY,
                "  client dlopen=FAIL\n"
                "  linker error=%s\n",
                error);

        dlerror();
        int runtime_close = dlclose(runtime_handle);
        appendf(report, REPORT_CAPACITY,
                "  runtime cleanup=%s\n"
                "  chain result=FAIL\n",
                runtime_close == 0 ? "PASS" : "FAIL");
        return false;
    }

    appendf(report, REPORT_CAPACITY,
            "  client dlopen=PASS\n"
            "  client visible after load=%s\n"
            "  explicit symbols invoked=NO\n",
            find_loaded_module(HTTP_CLIENT_NAME, loaded_path, sizeof(loaded_path))
                    ? "YES" : "NO");

    bool close_passed = true;

    dlerror();
    if (dlclose(client_handle) != 0) {
        appendf(report, REPORT_CAPACITY,
                "  client dlclose=FAIL\n"
                "  close error=%s\n",
                current_dl_error());
        close_passed = false;
    } else {
        appendf(report, REPORT_CAPACITY, "  client dlclose=PASS\n");
    }

    dlerror();
    if (dlclose(runtime_handle) != 0) {
        appendf(report, REPORT_CAPACITY,
                "  runtime dlclose=FAIL\n"
                "  close error=%s\n",
                current_dl_error());
        close_passed = false;
    } else {
        appendf(report, REPORT_CAPACITY, "  runtime dlclose=PASS\n");
    }

    appendf(report, REPORT_CAPACITY,
            "  client loaded after close=%s\n"
            "  runtime loaded after close=%s\n"
            "  chain result=%s\n",
            find_loaded_module(HTTP_CLIENT_NAME, loaded_path, sizeof(loaded_path))
                    ? "YES" : "NO",
            find_loaded_module(CPP_RUNTIME_NAME, loaded_path, sizeof(loaded_path))
                    ? "YES" : "NO",
            close_passed ? "PASS" : "FAIL");
    return close_passed;
}

static jstring run_linker_lab(JNIEnv* env, const char* requested_directory) {
    char* report = (char*) calloc(REPORT_CAPACITY, 1);
    if (report == NULL) {
        return (*env)->NewStringUTF(env, "MATH C LINKER BRIDGE\nAllocation failed.");
    }

    appendf(report, REPORT_CAPACITY,
            "MATH BEDROCK LINKER LOAD LAB\n"
            "Stage: 3.3.2\n"
            "Bridge: DEDICATED C LIBRARY (NO C++ RUNTIME)\n"
            "Process policy: SECONDARY APP PROCESS (:linker_lab)\n"
            "Standalone flags: RTLD_NOW | RTLD_LOCAL\n"
            "Runtime flags: RTLD_NOW | RTLD_GLOBAL\n"
            "Explicit exported symbols called: NONE\n"
            "ELF constructors: MAY RUN DURING DLOPEN\n"
            "libminecraftpe.so: BLOCKED BY POLICY\n\n");

    char directory[PATH_MAX];
    if (!canonical_path(requested_directory, directory, sizeof(directory))) {
        appendf(report, REPORT_CAPACITY,
                "Directory gate: BLOCK\n"
                "Linker verdict: INVALID RUNTIME DIRECTORY");
        jstring result = (*env)->NewStringUTF(env, report);
        free(report);
        return result;
    }

    appendf(report, REPORT_CAPACITY,
            "=== DIRECTORY GATE ===\n"
            "Runtime directory: %s\n"
            "Canonical path: PASS\n\n"
            "=== PROCESS BASELINE ===\n",
            directory);

    char baseline_path[PATH_MAX] = {0};
    bool cpp_preloaded = find_loaded_module(
            CPP_RUNTIME_NAME,
            baseline_path,
            sizeof(baseline_path));
    appendf(report, REPORT_CAPACITY,
            "C linker bridge loaded: YES\n"
            "libc++_shared.so at process start: %s\n",
            cpp_preloaded ? "YES" : "NO");
    if (cpp_preloaded) {
        appendf(report, REPORT_CAPACITY,
                "Preloaded path: %s\n",
                baseline_path);
    }
    appendf(report, REPORT_CAPACITY, "\n=== LOAD TESTS ===\n");

    int passed = 0;
    int failed = 0;

    if (test_standalone_library(directory, report)) {
        ++passed;
    } else {
        ++failed;
    }

    if (test_http_chain(directory, report)) {
        ++passed;
    } else {
        ++failed;
    }

    appendf(report, REPORT_CAPACITY,
            "\n=== LINKER VERDICT ===\n"
            "Test groups: 2\n"
            "Passed: %d\n"
            "Failed: %d\n"
            "Minecraft library attempted: NO\n"
            "Linker verdict: %s",
            passed,
            failed,
            failed == 0 && passed == 2
                    ? "READY FOR STAGE 3.4"
                    : "BLOCKED FOR DIAGNOSIS");

    __android_log_print(
            ANDROID_LOG_INFO,
            LOG_TAG,
            "Stage 3.3.2 finished: passed=%d failed=%d",
            passed,
            failed);

    jstring result = (*env)->NewStringUTF(env, report);
    free(report);
    return result;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void) vm;
    (void) reserved;
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "libmathlinker.so loaded");
    return JNI_VERSION_1_6;
}

JNIEXPORT jstring JNICALL
Java_com_balzikz_mathclient_LinkerBridge_nativeRunLinkerLoadTest(
        JNIEnv* env,
        jclass clazz,
        jstring runtime_directory) {
    (void) clazz;
    if (runtime_directory == NULL) {
        return (*env)->NewStringUTF(env, "MATH C LINKER BRIDGE\nRuntime directory is null.");
    }

    const char* directory = (*env)->GetStringUTFChars(env, runtime_directory, NULL);
    if (directory == NULL) {
        return (*env)->NewStringUTF(env, "MATH C LINKER BRIDGE\nCannot read runtime directory.");
    }

    jstring result = run_linker_lab(env, directory);
    (*env)->ReleaseStringUTFChars(env, runtime_directory, directory);
    return result;
}
