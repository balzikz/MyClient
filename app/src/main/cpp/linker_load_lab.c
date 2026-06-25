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

#define REPORT_CAPACITY (64 * 1024)
#define LIBRARY_COUNT 8

static const char* LOG_TAG = "MATH-C-LINKER";

typedef struct {
    const char* name;
    const char* role;
    int flags;
} LibrarySpec;

typedef struct {
    const char* target;
    bool found;
    char path[PATH_MAX];
} ModuleQuery;

static const LibrarySpec LIBRARIES[LIBRARY_COUNT] = {
        {"libc++_shared.so", "Bedrock C++ runtime", RTLD_NOW | RTLD_GLOBAL},
        {"libfmod.so", "audio dependency", RTLD_NOW | RTLD_LOCAL},
        {"libHttpClient.Android.so", "HTTP dependency", RTLD_NOW | RTLD_LOCAL},
        {"libmaesdk.so", "Microsoft account services dependency", RTLD_NOW | RTLD_LOCAL},
        {"libPlayFabMultiplayer.so", "PlayFab multiplayer dependency", RTLD_NOW | RTLD_LOCAL},
        {"libMediaDecoders_Android.so", "Android media decoder dependency", RTLD_NOW | RTLD_LOCAL},
        {"libconscrypt_jni.so", "Conscrypt JNI dependency", RTLD_NOW | RTLD_LOCAL},
        {"libmcfix.so", "Bedrock support dependency", RTLD_NOW | RTLD_LOCAL},
};

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

static void cleanup_loaded_handles(
        void** handles,
        int loaded_count,
        char* report,
        int* close_failures) {
    appendf(report, REPORT_CAPACITY, "\n=== REVERSE CLEANUP ===\n");
    for (int index = loaded_count - 1; index >= 0; --index) {
        if (handles[index] == NULL) {
            continue;
        }

        dlerror();
        int result = dlclose(handles[index]);
        if (result == 0) {
            appendf(report, REPORT_CAPACITY,
                    "[CLOSE PASS] %s\n",
                    LIBRARIES[index].name);
        } else {
            appendf(report, REPORT_CAPACITY,
                    "[CLOSE FAIL] %s\n"
                    "  close error=%s\n",
                    LIBRARIES[index].name,
                    current_dl_error());
            ++(*close_failures);
        }
        handles[index] = NULL;
    }
}

static bool run_full_small_library_chain(const char* directory, char* report) {
    char paths[LIBRARY_COUNT][PATH_MAX];
    long long sizes[LIBRARY_COUNT];
    void* handles[LIBRARY_COUNT];
    memset(paths, 0, sizeof(paths));
    memset(sizes, 0, sizeof(sizes));
    memset(handles, 0, sizeof(handles));

    appendf(report, REPORT_CAPACITY, "=== PATH GATE ===\n");
    for (int index = 0; index < LIBRARY_COUNT; ++index) {
        appendf(report, REPORT_CAPACITY,
                "[FILE %d/%d] %s\n"
                "  role=%s\n",
                index + 1,
                LIBRARY_COUNT,
                LIBRARIES[index].name,
                LIBRARIES[index].role);

        if (!validate_library_path(
                directory,
                LIBRARIES[index].name,
                paths[index],
                &sizes[index],
                report)) {
            appendf(report, REPORT_CAPACITY,
                    "Path verdict: BLOCKED AT %s\n",
                    LIBRARIES[index].name);
            return false;
        }
    }

    appendf(report, REPORT_CAPACITY, "\n=== PROCESS BASELINE ===\n");
    bool baseline_clean = true;
    for (int index = 0; index < LIBRARY_COUNT; ++index) {
        char loaded_path[PATH_MAX] = {0};
        bool loaded = find_loaded_module(
                LIBRARIES[index].name,
                loaded_path,
                sizeof(loaded_path));
        appendf(report, REPORT_CAPACITY,
                "%s: %s\n",
                LIBRARIES[index].name,
                loaded ? "PRELOADED" : "NOT LOADED");
        if (loaded) {
            appendf(report, REPORT_CAPACITY,
                    "  loaded path=%s\n",
                    loaded_path);
            baseline_clean = false;
        }
    }

    if (!baseline_clean) {
        appendf(report, REPORT_CAPACITY,
                "Baseline verdict: BLOCKED BY PRELOADED MODULE\n");
        return false;
    }
    appendf(report, REPORT_CAPACITY, "Baseline verdict: CLEAN\n");

    appendf(report, REPORT_CAPACITY, "\n=== ORDERED LOAD CHAIN ===\n");
    int loaded_count = 0;
    int close_failures = 0;

    for (int index = 0; index < LIBRARY_COUNT; ++index) {
        const char* visibility = index == 0 ? "GLOBAL" : "LOCAL";
        appendf(report, REPORT_CAPACITY,
                "[LOAD %d/%d] %s\n"
                "  role=%s\n"
                "  visibility=%s\n",
                index + 1,
                LIBRARY_COUNT,
                LIBRARIES[index].name,
                LIBRARIES[index].role,
                visibility);

        dlerror();
        handles[index] = dlopen(paths[index], LIBRARIES[index].flags);
        if (handles[index] == NULL) {
            appendf(report, REPORT_CAPACITY,
                    "  dlopen=FAIL\n"
                    "  linker error=%s\n"
                    "  load chain=BLOCKED AT %s\n",
                    current_dl_error(),
                    LIBRARIES[index].name);
            cleanup_loaded_handles(handles, loaded_count, report, &close_failures);
            appendf(report, REPORT_CAPACITY,
                    "Cleanup failures: %d\n",
                    close_failures);
            return false;
        }

        ++loaded_count;
        char loaded_path[PATH_MAX] = {0};
        bool visible = find_loaded_module(
                LIBRARIES[index].name,
                loaded_path,
                sizeof(loaded_path));
        appendf(report, REPORT_CAPACITY,
                "  dlopen=PASS\n"
                "  visible after load=%s\n"
                "  explicit symbols invoked=NO\n"
                "  JNI_OnLoad explicitly invoked=NO\n",
                visible ? "YES" : "NO");
        if (visible) {
            appendf(report, REPORT_CAPACITY,
                    "  loaded path=%s\n",
                    loaded_path);
        }
    }

    appendf(report, REPORT_CAPACITY,
            "\nSimultaneous handles: %d/%d\n"
            "Ordered load chain: PASS\n",
            loaded_count,
            LIBRARY_COUNT);

    cleanup_loaded_handles(handles, loaded_count, report, &close_failures);

    appendf(report, REPORT_CAPACITY, "\n=== POST-CLOSE STATE ===\n");
    int retained_count = 0;
    for (int index = 0; index < LIBRARY_COUNT; ++index) {
        char loaded_path[PATH_MAX] = {0};
        bool retained = find_loaded_module(
                LIBRARIES[index].name,
                loaded_path,
                sizeof(loaded_path));
        appendf(report, REPORT_CAPACITY,
                "%s: %s\n",
                LIBRARIES[index].name,
                retained ? "RETAINED BY LINKER" : "UNLOADED");
        if (retained) {
            ++retained_count;
            appendf(report, REPORT_CAPACITY,
                    "  retained path=%s\n",
                    loaded_path);
        }
    }

    appendf(report, REPORT_CAPACITY,
            "Close failures: %d\n"
            "Retained modules after successful dlclose: %d\n",
            close_failures,
            retained_count);
    return close_failures == 0;
}

static jstring run_linker_lab(JNIEnv* env, const char* requested_directory) {
    char* report = (char*) calloc(REPORT_CAPACITY, 1);
    if (report == NULL) {
        return (*env)->NewStringUTF(env, "MATH C LINKER BRIDGE\nAllocation failed.");
    }

    appendf(report, REPORT_CAPACITY,
            "MATH BEDROCK SMALL RUNTIME CHAIN LAB\n"
            "Stage: 3.4\n"
            "Bridge: DEDICATED C LIBRARY (NO C++ RUNTIME)\n"
            "Mode: 8-LIBRARY ORDERED DLOPEN + REVERSE DLCLOSE\n"
            "Process policy: SECONDARY APP PROCESS (:linker_lab)\n"
            "C++ runtime flags: RTLD_NOW | RTLD_GLOBAL\n"
            "Dependency flags: RTLD_NOW | RTLD_LOCAL\n"
            "Explicit exported symbols called: NONE\n"
            "JNI_OnLoad explicitly invoked: NONE\n"
            "ELF constructors: MAY RUN DURING DLOPEN\n"
            "libminecraftpe.so: BLOCKED BY POLICY\n\n");

    char directory[PATH_MAX];
    if (!canonical_path(requested_directory, directory, sizeof(directory))) {
        appendf(report, REPORT_CAPACITY,
                "Directory gate: BLOCK\n"
                "Runtime verdict: INVALID RUNTIME DIRECTORY");
        jstring result = (*env)->NewStringUTF(env, report);
        free(report);
        return result;
    }

    appendf(report, REPORT_CAPACITY,
            "=== DIRECTORY GATE ===\n"
            "Runtime directory: %s\n"
            "Canonical path: PASS\n\n",
            directory);

    bool passed = run_full_small_library_chain(directory, report);

    appendf(report, REPORT_CAPACITY,
            "\n=== RUNTIME VERDICT ===\n"
            "Small libraries planned: %d\n"
            "Minecraft library attempted: NO\n"
            "Runtime verdict: %s",
            LIBRARY_COUNT,
            passed
                    ? "READY FOR STAGE 3.5"
                    : "BLOCKED FOR DIAGNOSIS");

    __android_log_print(
            ANDROID_LOG_INFO,
            LOG_TAG,
            "Stage 3.4 finished: %s",
            passed ? "PASS" : "FAIL");

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
