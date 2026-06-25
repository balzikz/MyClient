#define _GNU_SOURCE

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <link.h>
#include <limits.h>
#include <sys/stat.h>
#include <unistd.h>

#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MC_REPORT_CAPACITY (96 * 1024)
#define MC_DEPENDENCY_COUNT 8

static const char* MC_LOG_TAG = "MATH-MC-LOAD";
static const char* MC_GAME_LIBRARY = "libminecraftpe.so";
static const char* MC_JOURNAL_NAME = "stage-3.5-journal.txt";

static const char* MC_DEPENDENCIES[MC_DEPENDENCY_COUNT] = {
        "libc++_shared.so",
        "libfmod.so",
        "libHttpClient.Android.so",
        "libmaesdk.so",
        "libPlayFabMultiplayer.so",
        "libMediaDecoders_Android.so",
        "libconscrypt_jni.so",
        "libmcfix.so",
};

typedef struct {
    const char* target;
    bool found;
    char path[PATH_MAX];
} McModuleQuery;

static void mc_appendf(char* report, size_t capacity, const char* format, ...) {
    size_t used = strnlen(report, capacity);
    if (used >= capacity - 1) {
        return;
    }

    va_list args;
    va_start(args, format);
    vsnprintf(report + used, capacity - used, format, args);
    va_end(args);
}

static const char* mc_base_name(const char* path) {
    if (path == NULL) {
        return "";
    }
    const char* slash = strrchr(path, '/');
    return slash == NULL ? path : slash + 1;
}

static int mc_find_module_callback(struct dl_phdr_info* info, size_t size, void* data) {
    (void) size;
    if (info == NULL || data == NULL || info->dlpi_name == NULL) {
        return 0;
    }

    McModuleQuery* query = (McModuleQuery*) data;
    if (info->dlpi_name[0] != '\0'
            && strcmp(mc_base_name(info->dlpi_name), query->target) == 0) {
        query->found = true;
        snprintf(query->path, sizeof(query->path), "%s", info->dlpi_name);
        return 1;
    }
    return 0;
}

static bool mc_find_loaded_module(const char* name, char* path, size_t path_capacity) {
    McModuleQuery query;
    memset(&query, 0, sizeof(query));
    query.target = name;
    dl_iterate_phdr(mc_find_module_callback, &query);

    if (query.found && path != NULL && path_capacity > 0) {
        snprintf(path, path_capacity, "%s", query.path);
    }
    return query.found;
}

static bool mc_canonical_path(const char* input, char* output, size_t output_capacity) {
    if (input == NULL || output == NULL || output_capacity < PATH_MAX) {
        return false;
    }
    return realpath(input, output) != NULL;
}

static bool mc_regular_readable_file(const char* path, long long* size) {
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

static bool mc_resolve_library(
        const char* directory,
        const char* name,
        char* resolved,
        long long* size,
        char* report) {
    char candidate[PATH_MAX];
    snprintf(candidate, sizeof(candidate), "%s/%s", directory, name);

    if (!mc_canonical_path(candidate, resolved, PATH_MAX)) {
        mc_appendf(report, MC_REPORT_CAPACITY,
                "  path=UNRESOLVED\n"
                "  path gate=BLOCK\n");
        return false;
    }

    size_t directory_length = strlen(directory);
    bool inside_directory = strncmp(resolved, directory, directory_length) == 0
            && resolved[directory_length] == '/';

    mc_appendf(report, MC_REPORT_CAPACITY, "  path=%s\n", resolved);
    if (!inside_directory) {
        mc_appendf(report, MC_REPORT_CAPACITY,
                "  path gate=BLOCK ESCAPED DIRECTORY\n");
        return false;
    }

    if (!mc_regular_readable_file(resolved, size)) {
        mc_appendf(report, MC_REPORT_CAPACITY, "  file gate=BLOCK\n");
        return false;
    }

    mc_appendf(report, MC_REPORT_CAPACITY,
            "  size=%lld bytes\n"
            "  path gate=PASS\n"
            "  file gate=PASS\n",
            *size);
    return true;
}

static void mc_copy_dl_error(char* output, size_t output_capacity) {
    const char* error = dlerror();
    snprintf(output, output_capacity, "%s", error == NULL ? "unknown" : error);
}

static bool mc_write_journal(const char* directory, const char* text) {
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/%s", directory, MC_JOURNAL_NAME);

    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) {
        return false;
    }

    size_t length = strlen(text);
    size_t written = 0;
    while (written < length) {
        ssize_t count = write(fd, text + written, length - written);
        if (count <= 0) {
            close(fd);
            return false;
        }
        written += (size_t) count;
    }

    bool synced = fsync(fd) == 0;
    bool closed = close(fd) == 0;
    return synced && closed;
}

static void mc_write_step_journal(
        const char* directory,
        const char* status,
        const char* library,
        const char* detail) {
    char value[4096];
    snprintf(value, sizeof(value),
            "MATH STAGE 3.5 JOURNAL\n"
            "status=%s\n"
            "library=%s\n"
            "detail=%s\n",
            status == NULL ? "UNKNOWN" : status,
            library == NULL ? "NONE" : library,
            detail == NULL ? "NONE" : detail);
    mc_write_journal(directory, value);
}

static void mc_cleanup_dependencies(
        void** handles,
        int loaded_count,
        char* report,
        int* close_failures) {
    mc_appendf(report, MC_REPORT_CAPACITY, "\n=== DEPENDENCY CLEANUP ===\n");
    for (int index = loaded_count - 1; index >= 0; --index) {
        if (handles[index] == NULL) {
            continue;
        }

        dlerror();
        int result = dlclose(handles[index]);
        if (result == 0) {
            mc_appendf(report, MC_REPORT_CAPACITY,
                    "[CLOSE PASS] %s\n",
                    MC_DEPENDENCIES[index]);
        } else {
            char error[2048];
            mc_copy_dl_error(error, sizeof(error));
            mc_appendf(report, MC_REPORT_CAPACITY,
                    "[CLOSE FAIL] %s\n"
                    "  close error=%s\n",
                    MC_DEPENDENCIES[index],
                    error);
            ++(*close_failures);
        }
        handles[index] = NULL;
    }
}

static jstring mc_run_load_lab(JNIEnv* env, const char* requested_directory) {
    char* report = (char*) calloc(MC_REPORT_CAPACITY, 1);
    if (report == NULL) {
        return (*env)->NewStringUTF(env,
                "MATH MINECRAFT LOAD LAB\nAllocation failed.");
    }

    mc_appendf(report, MC_REPORT_CAPACITY,
            "MATH BEDROCK MINECRAFT DLOPEN LAB\n"
            "Stage: 3.5\n"
            "Bridge: DEDICATED C LIBRARY (NO C++ RUNTIME)\n"
            "Process policy: ISOLATED SECONDARY APP PROCESS (:minecraft_lab)\n"
            "Dependency flags: RTLD_NOW | RTLD_GLOBAL\n"
            "Minecraft flags: RTLD_NOW | RTLD_LOCAL\n"
            "Explicit exported symbols called: NONE\n"
            "JNI_OnLoad explicitly invoked: NO\n"
            "ANativeActivity_onCreate invoked: NO\n"
            "ELF constructors: MAY RUN DURING DLOPEN\n"
            "Crash journal: ENABLED + FSYNC BEFORE MINECRAFT DLOPEN\n\n");

    char directory[PATH_MAX];
    if (!mc_canonical_path(requested_directory, directory, sizeof(directory))) {
        mc_appendf(report, MC_REPORT_CAPACITY,
                "Directory gate: BLOCK\n"
                "Minecraft load verdict: INVALID RUNTIME DIRECTORY");
        jstring result = (*env)->NewStringUTF(env, report);
        free(report);
        return result;
    }

    mc_appendf(report, MC_REPORT_CAPACITY,
            "=== DIRECTORY GATE ===\n"
            "Runtime directory: %s\n"
            "Canonical path: PASS\n\n",
            directory);

    char dependency_paths[MC_DEPENDENCY_COUNT][PATH_MAX];
    long long dependency_sizes[MC_DEPENDENCY_COUNT];
    void* dependency_handles[MC_DEPENDENCY_COUNT];
    memset(dependency_paths, 0, sizeof(dependency_paths));
    memset(dependency_sizes, 0, sizeof(dependency_sizes));
    memset(dependency_handles, 0, sizeof(dependency_handles));

    char minecraft_path[PATH_MAX];
    long long minecraft_size = 0;

    mc_appendf(report, MC_REPORT_CAPACITY, "=== PATH GATE ===\n");
    for (int index = 0; index < MC_DEPENDENCY_COUNT; ++index) {
        mc_appendf(report, MC_REPORT_CAPACITY,
                "[DEPENDENCY %d/%d] %s\n",
                index + 1,
                MC_DEPENDENCY_COUNT,
                MC_DEPENDENCIES[index]);
        if (!mc_resolve_library(
                directory,
                MC_DEPENDENCIES[index],
                dependency_paths[index],
                &dependency_sizes[index],
                report)) {
            mc_appendf(report, MC_REPORT_CAPACITY,
                    "Minecraft load verdict: BLOCKED AT PATH GATE\n");
            jstring result = (*env)->NewStringUTF(env, report);
            free(report);
            return result;
        }
    }

    mc_appendf(report, MC_REPORT_CAPACITY,
            "[MINECRAFT] %s\n",
            MC_GAME_LIBRARY);
    if (!mc_resolve_library(
            directory,
            MC_GAME_LIBRARY,
            minecraft_path,
            &minecraft_size,
            report)) {
        mc_appendf(report, MC_REPORT_CAPACITY,
                "Minecraft load verdict: BLOCKED AT MINECRAFT PATH GATE\n");
        jstring result = (*env)->NewStringUTF(env, report);
        free(report);
        return result;
    }

    mc_appendf(report, MC_REPORT_CAPACITY, "\n=== PROCESS BASELINE ===\n");
    bool baseline_clean = true;
    for (int index = 0; index < MC_DEPENDENCY_COUNT; ++index) {
        char loaded_path[PATH_MAX] = {0};
        bool loaded = mc_find_loaded_module(
                MC_DEPENDENCIES[index],
                loaded_path,
                sizeof(loaded_path));
        mc_appendf(report, MC_REPORT_CAPACITY,
                "%s: %s\n",
                MC_DEPENDENCIES[index],
                loaded ? "PRELOADED" : "NOT LOADED");
        if (loaded) {
            baseline_clean = false;
            mc_appendf(report, MC_REPORT_CAPACITY,
                    "  loaded path=%s\n",
                    loaded_path);
        }
    }

    char minecraft_loaded_path[PATH_MAX] = {0};
    bool minecraft_preloaded = mc_find_loaded_module(
            MC_GAME_LIBRARY,
            minecraft_loaded_path,
            sizeof(minecraft_loaded_path));
    mc_appendf(report, MC_REPORT_CAPACITY,
            "%s: %s\n",
            MC_GAME_LIBRARY,
            minecraft_preloaded ? "PRELOADED" : "NOT LOADED");
    if (minecraft_preloaded) {
        baseline_clean = false;
        mc_appendf(report, MC_REPORT_CAPACITY,
                "  loaded path=%s\n",
                minecraft_loaded_path);
    }

    if (!baseline_clean) {
        mc_appendf(report, MC_REPORT_CAPACITY,
                "Baseline verdict: BLOCKED BY PRELOADED MODULE\n"
                "Minecraft load verdict: BLOCKED FOR DIAGNOSIS\n");
        jstring result = (*env)->NewStringUTF(env, report);
        free(report);
        return result;
    }
    mc_appendf(report, MC_REPORT_CAPACITY, "Baseline verdict: CLEAN\n");

    mc_write_step_journal(directory, "START", "NONE", "baseline clean");

    mc_appendf(report, MC_REPORT_CAPACITY,
            "\n=== GLOBAL DEPENDENCY PRELOAD ===\n");
    int loaded_count = 0;
    int close_failures = 0;

    for (int index = 0; index < MC_DEPENDENCY_COUNT; ++index) {
        mc_appendf(report, MC_REPORT_CAPACITY,
                "[PRELOAD %d/%d] %s\n"
                "  flags=RTLD_NOW | RTLD_GLOBAL\n",
                index + 1,
                MC_DEPENDENCY_COUNT,
                MC_DEPENDENCIES[index]);

        mc_write_step_journal(
                directory,
                "BEFORE_DEPENDENCY_DLOPEN",
                MC_DEPENDENCIES[index],
                "RTLD_NOW|RTLD_GLOBAL");

        dlerror();
        dependency_handles[index] = dlopen(
                dependency_paths[index],
                RTLD_NOW | RTLD_GLOBAL);
        if (dependency_handles[index] == NULL) {
            char error[2048];
            mc_copy_dl_error(error, sizeof(error));
            mc_write_step_journal(
                    directory,
                    "DEPENDENCY_DLOPEN_FAIL",
                    MC_DEPENDENCIES[index],
                    error);
            mc_appendf(report, MC_REPORT_CAPACITY,
                    "  dlopen=FAIL\n"
                    "  linker error=%s\n"
                    "  dependency preload=BLOCKED AT %s\n",
                    error,
                    MC_DEPENDENCIES[index]);
            mc_cleanup_dependencies(
                    dependency_handles,
                    loaded_count,
                    report,
                    &close_failures);
            mc_appendf(report, MC_REPORT_CAPACITY,
                    "Minecraft library attempted: NO\n"
                    "Minecraft load verdict: BLOCKED FOR DIAGNOSIS\n");
            jstring result = (*env)->NewStringUTF(env, report);
            free(report);
            return result;
        }

        ++loaded_count;
        char loaded_path[PATH_MAX] = {0};
        bool visible = mc_find_loaded_module(
                MC_DEPENDENCIES[index],
                loaded_path,
                sizeof(loaded_path));
        mc_appendf(report, MC_REPORT_CAPACITY,
                "  dlopen=PASS\n"
                "  visible after load=%s\n"
                "  explicit symbols invoked=NO\n",
                visible ? "YES" : "NO");
        if (visible) {
            mc_appendf(report, MC_REPORT_CAPACITY,
                    "  loaded path=%s\n",
                    loaded_path);
        }

        mc_write_step_journal(
                directory,
                "DEPENDENCY_DLOPEN_PASS",
                MC_DEPENDENCIES[index],
                "handle retained");
    }

    mc_appendf(report, MC_REPORT_CAPACITY,
            "Dependencies retained: %d/%d\n",
            loaded_count,
            MC_DEPENDENCY_COUNT);

    mc_appendf(report, MC_REPORT_CAPACITY,
            "\n=== MINECRAFT DLOPEN ATTEMPT ===\n"
            "Library: %s\n"
            "Flags: RTLD_NOW | RTLD_LOCAL\n"
            "Explicit symbols invoked before dlopen: NO\n",
            MC_GAME_LIBRARY);

    mc_write_step_journal(
            directory,
            "BEFORE_MINECRAFT_DLOPEN",
            MC_GAME_LIBRARY,
            "journal fsynced; constructors may run next");

    dlerror();
    void* minecraft_handle = dlopen(
            minecraft_path,
            RTLD_NOW | RTLD_LOCAL);

    if (minecraft_handle == NULL) {
        char error[4096];
        mc_copy_dl_error(error, sizeof(error));
        mc_write_step_journal(
                directory,
                "MINECRAFT_DLOPEN_FAIL",
                MC_GAME_LIBRARY,
                error);
        mc_appendf(report, MC_REPORT_CAPACITY,
                "dlopen=FAIL\n"
                "linker error=%s\n"
                "JNI_OnLoad explicitly invoked=NO\n"
                "ANativeActivity_onCreate invoked=NO\n",
                error);
        mc_cleanup_dependencies(
                dependency_handles,
                loaded_count,
                report,
                &close_failures);
        mc_appendf(report, MC_REPORT_CAPACITY,
                "Close failures: %d\n"
                "Minecraft library attempted: YES\n"
                "Minecraft load verdict: BLOCKED FOR DIAGNOSIS\n",
                close_failures);
        jstring result = (*env)->NewStringUTF(env, report);
        free(report);
        return result;
    }

    mc_write_step_journal(
            directory,
            "MINECRAFT_DLOPEN_PASS",
            MC_GAME_LIBRARY,
            "handle retained; no exports invoked");

    bool minecraft_visible = mc_find_loaded_module(
            MC_GAME_LIBRARY,
            minecraft_loaded_path,
            sizeof(minecraft_loaded_path));
    mc_appendf(report, MC_REPORT_CAPACITY,
            "dlopen=PASS\n"
            "visible after load=%s\n"
            "explicit symbols invoked=NO\n"
            "JNI_OnLoad explicitly invoked=NO\n"
            "ANativeActivity_onCreate invoked=NO\n",
            minecraft_visible ? "YES" : "NO");
    if (minecraft_visible) {
        mc_appendf(report, MC_REPORT_CAPACITY,
                "loaded path=%s\n",
                minecraft_loaded_path);
    }

    mc_appendf(report, MC_REPORT_CAPACITY,
            "\n=== MINECRAFT CLEANUP ===\n");
    dlerror();
    int minecraft_close = dlclose(minecraft_handle);
    if (minecraft_close == 0) {
        mc_appendf(report, MC_REPORT_CAPACITY,
                "Minecraft dlclose=PASS\n");
    } else {
        char error[2048];
        mc_copy_dl_error(error, sizeof(error));
        mc_appendf(report, MC_REPORT_CAPACITY,
                "Minecraft dlclose=FAIL\n"
                "close error=%s\n",
                error);
        ++close_failures;
    }

    mc_cleanup_dependencies(
            dependency_handles,
            loaded_count,
            report,
            &close_failures);

    minecraft_visible = mc_find_loaded_module(
            MC_GAME_LIBRARY,
            minecraft_loaded_path,
            sizeof(minecraft_loaded_path));
    mc_appendf(report, MC_REPORT_CAPACITY,
            "\n=== POST-CLOSE STATE ===\n"
            "%s: %s\n",
            MC_GAME_LIBRARY,
            minecraft_visible ? "RETAINED BY LINKER" : "UNLOADED");
    if (minecraft_visible) {
        mc_appendf(report, MC_REPORT_CAPACITY,
                "  retained path=%s\n",
                minecraft_loaded_path);
    }
    mc_appendf(report, MC_REPORT_CAPACITY,
            "Close failures: %d\n",
            close_failures);

    bool passed = close_failures == 0;
    mc_write_step_journal(
            directory,
            passed ? "COMPLETE_PASS" : "COMPLETE_CLOSE_FAILURE",
            MC_GAME_LIBRARY,
            passed ? "dlopen pass; no exports invoked" : "one or more dlclose failures");

    mc_appendf(report, MC_REPORT_CAPACITY,
            "\n=== MINECRAFT LOAD VERDICT ===\n"
            "Dependencies loaded globally: %d/%d\n"
            "Minecraft library attempted: YES\n"
            "Minecraft dlopen: PASS\n"
            "Game entrypoint invoked: NO\n"
            "Minecraft load verdict: %s",
            loaded_count,
            MC_DEPENDENCY_COUNT,
            passed
                    ? "READY FOR STAGE 3.6"
                    : "DLOPEN PASS WITH CLEANUP FAILURE");

    __android_log_print(
            ANDROID_LOG_INFO,
            MC_LOG_TAG,
            "Stage 3.5 finished: %s",
            passed ? "PASS" : "CLOSE FAILURE");

    jstring result = (*env)->NewStringUTF(env, report);
    free(report);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_balzikz_mathclient_LinkerBridge_nativeRunMinecraftLoadTest(
        JNIEnv* env,
        jclass clazz,
        jstring runtime_directory) {
    (void) clazz;
    if (runtime_directory == NULL) {
        return (*env)->NewStringUTF(env,
                "MATH MINECRAFT LOAD LAB\nRuntime directory is null.");
    }

    const char* directory = (*env)->GetStringUTFChars(
            env,
            runtime_directory,
            NULL);
    if (directory == NULL) {
        return (*env)->NewStringUTF(env,
                "MATH MINECRAFT LOAD LAB\nCannot read runtime directory.");
    }

    jstring result = mc_run_load_lab(env, directory);
    (*env)->ReleaseStringUTFChars(env, runtime_directory, directory);
    return result;
}
