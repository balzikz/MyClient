#define _GNU_SOURCE

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <limits.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define PROBE_REPORT_CAPACITY (64 * 1024)
#define PROBE_DEPENDENCY_COUNT 8

static const char* PROBE_LOG_TAG = "MATH-MC-PROBE";
static const char* PROBE_GAME_LIBRARY = "libminecraftpe.so";

static const char* PROBE_DEPENDENCIES[PROBE_DEPENDENCY_COUNT] = {
        "libc++_shared.so",
        "libfmod.so",
        "libHttpClient.Android.so",
        "libmaesdk.so",
        "libPlayFabMultiplayer.so",
        "libMediaDecoders_Android.so",
        "libconscrypt_jni.so",
        "libmcfix.so",
};

static const char* PROBE_SYMBOLS[] = {
        "JNI_OnLoad",
        "ANativeActivity_onCreate",
        "android_main",
        "Java_com_mojang_minecraftpe_MainActivity_nativeInitialize",
        "Java_com_mojang_minecraftpe_MainActivity_nativeOnCreate",
        "Java_com_mojang_minecraftpe_MainActivity_nativeOnResume",
        "Java_com_mojang_minecraftpe_MainActivity_nativeOnPause",
        "Java_com_mojang_minecraftpe_MainActivity_nativeOnDestroy",
        "Java_com_mojang_minecraftpe_MainActivity_nativeBackPressed",
        "Java_com_mojang_minecraftpe_MainActivity_nativeSetTextboxText",
        "Java_com_mojang_minecraftpe_MainActivity_nativeKeyHandler",
        "Java_com_mojang_minecraftpe_MainActivity_nativeMouseButton",
};

static const char* probe_base_name(const char* path) {
    if (path == NULL) {
        return "";
    }
    const char* slash = strrchr(path, '/');
    return slash == NULL ? path : slash + 1;
}

static void probe_append(char* report, const char* text) {
    size_t used = strnlen(report, PROBE_REPORT_CAPACITY);
    if (used >= PROBE_REPORT_CAPACITY - 1) {
        return;
    }
    snprintf(report + used, PROBE_REPORT_CAPACITY - used, "%s", text);
}

static bool probe_resolve_library(
        const char* directory,
        const char* name,
        char* resolved,
        long long* size) {
    char candidate[PATH_MAX];
    snprintf(candidate, sizeof(candidate), "%s/%s", directory, name);

    if (realpath(candidate, resolved) == NULL) {
        return false;
    }

    size_t directory_length = strlen(directory);
    if (strncmp(resolved, directory, directory_length) != 0
            || resolved[directory_length] != '/') {
        return false;
    }

    struct stat info;
    memset(&info, 0, sizeof(info));
    if (stat(resolved, &info) != 0
            || !S_ISREG(info.st_mode)
            || access(resolved, R_OK) != 0) {
        return false;
    }

    if (size != NULL) {
        *size = (long long) info.st_size;
    }
    return true;
}

static void probe_cleanup(void** handles, int count) {
    for (int index = count - 1; index >= 0; --index) {
        if (handles[index] != NULL) {
            dlclose(handles[index]);
            handles[index] = NULL;
        }
    }
}

static jstring probe_run(JNIEnv* env, const char* requested_directory) {
    char* report = (char*) calloc(PROBE_REPORT_CAPACITY, 1);
    if (report == NULL) {
        return (*env)->NewStringUTF(env, "MATH STAGE 3.6\nAllocation failed.");
    }

    probe_append(report,
            "MATH BEDROCK ENTRYPOINT PROBE\n"
            "Stage: 3.6\n"
            "Process policy: ISOLATED SECONDARY APP PROCESS (:minecraft_probe)\n"
            "Dependency flags: RTLD_NOW | RTLD_GLOBAL\n"
            "Minecraft flags: RTLD_NOW | RTLD_LOCAL\n"
            "Method: dlsym + dladdr ONLY\n"
            "Ownership rule: COUNT ONLY SYMBOLS OWNED BY libminecraftpe.so\n"
            "Explicit exported symbols invoked: NONE\n"
            "JNI_OnLoad invoked: NO\n"
            "ANativeActivity_onCreate invoked: NO\n"
            "android_main invoked: NO\n\n");

    char directory[PATH_MAX];
    if (realpath(requested_directory, directory) == NULL) {
        probe_append(report,
                "Directory gate: BLOCK\n"
                "Probe verdict: INVALID RUNTIME DIRECTORY");
        jstring result = (*env)->NewStringUTF(env, report);
        free(report);
        return result;
    }

    char directory_line[PATH_MAX * 2 + 128];
    snprintf(directory_line, sizeof(directory_line),
            "Runtime directory: %s\nCanonical path: %s\n\n",
            requested_directory,
            directory);
    probe_append(report, directory_line);

    char dependency_paths[PROBE_DEPENDENCY_COUNT][PATH_MAX];
    void* dependency_handles[PROBE_DEPENDENCY_COUNT];
    memset(dependency_paths, 0, sizeof(dependency_paths));
    memset(dependency_handles, 0, sizeof(dependency_handles));

    char game_path[PATH_MAX];
    long long game_size = 0;

    probe_append(report, "=== PATH GATE ===\n");
    for (int index = 0; index < PROBE_DEPENDENCY_COUNT; ++index) {
        long long size = 0;
        if (!probe_resolve_library(
                directory,
                PROBE_DEPENDENCIES[index],
                dependency_paths[index],
                &size)) {
            char line[512];
            snprintf(line, sizeof(line),
                    "[BLOCK] %s\nProbe verdict: DEPENDENCY PATH FAILURE",
                    PROBE_DEPENDENCIES[index]);
            probe_append(report, line);
            jstring result = (*env)->NewStringUTF(env, report);
            free(report);
            return result;
        }
        char line[512];
        snprintf(line, sizeof(line),
                "[READY] %s | %lld bytes\n",
                PROBE_DEPENDENCIES[index],
                size);
        probe_append(report, line);
    }

    if (!probe_resolve_library(
            directory,
            PROBE_GAME_LIBRARY,
            game_path,
            &game_size)) {
        probe_append(report,
                "[BLOCK] libminecraftpe.so\n"
                "Probe verdict: MINECRAFT PATH FAILURE");
        jstring result = (*env)->NewStringUTF(env, report);
        free(report);
        return result;
    }

    char game_line[512];
    snprintf(game_line, sizeof(game_line),
            "[READY] %s | %lld bytes\n",
            PROBE_GAME_LIBRARY,
            game_size);
    probe_append(report, game_line);

    probe_append(report, "\n=== GLOBAL DEPENDENCY PRELOAD ===\n");
    int loaded_count = 0;
    for (int index = 0; index < PROBE_DEPENDENCY_COUNT; ++index) {
        dlerror();
        dependency_handles[index] = dlopen(
                dependency_paths[index],
                RTLD_NOW | RTLD_GLOBAL);
        if (dependency_handles[index] == NULL) {
            const char* error = dlerror();
            char line[4096];
            snprintf(line, sizeof(line),
                    "[FAIL] %s\nlinker error=%s\nProbe verdict: DEPENDENCY LOAD FAILURE",
                    PROBE_DEPENDENCIES[index],
                    error == NULL ? "unknown" : error);
            probe_append(report, line);
            probe_cleanup(dependency_handles, loaded_count);
            jstring result = (*env)->NewStringUTF(env, report);
            free(report);
            return result;
        }
        ++loaded_count;
        char line[256];
        snprintf(line, sizeof(line), "[PASS] %s\n", PROBE_DEPENDENCIES[index]);
        probe_append(report, line);
    }

    probe_append(report, "\n=== MINECRAFT LOAD ===\n");
    dlerror();
    void* game_handle = dlopen(game_path, RTLD_NOW | RTLD_LOCAL);
    if (game_handle == NULL) {
        const char* error = dlerror();
        char line[4096];
        snprintf(line, sizeof(line),
                "dlopen=FAIL\nlinker error=%s\nProbe verdict: MINECRAFT LOAD FAILURE",
                error == NULL ? "unknown" : error);
        probe_append(report, line);
        probe_cleanup(dependency_handles, loaded_count);
        jstring result = (*env)->NewStringUTF(env, report);
        free(report);
        return result;
    }

    probe_append(report,
            "dlopen=PASS\n"
            "explicit symbols invoked=NO\n\n"
            "=== DLSYM ENTRYPOINT TABLE ===\n");

    const int symbol_count = (int) (sizeof(PROBE_SYMBOLS) / sizeof(PROBE_SYMBOLS[0]));
    int resolved_count = 0;
    int game_owned_count = 0;
    bool native_activity_found = false;
    bool android_main_found = false;
    bool jni_onload_found = false;

    for (int index = 0; index < symbol_count; ++index) {
        const char* name = PROBE_SYMBOLS[index];
        dlerror();
        void* symbol = dlsym(game_handle, name);
        const char* error = dlerror();

        if (symbol == NULL || error != NULL) {
            char line[768];
            snprintf(line, sizeof(line), "[NOT FOUND] %s\n", name);
            probe_append(report, line);
            continue;
        }

        ++resolved_count;
        Dl_info info;
        memset(&info, 0, sizeof(info));
        bool described = dladdr(symbol, &info) != 0
                && info.dli_fbase != NULL
                && info.dli_fname != NULL;
        bool owned_by_game = described
                && strcmp(probe_base_name(info.dli_fname), PROBE_GAME_LIBRARY) == 0;
        uintptr_t offset = described
                ? (uintptr_t) symbol - (uintptr_t) info.dli_fbase
                : 0;

        if (owned_by_game) {
            ++game_owned_count;
            if (strcmp(name, "ANativeActivity_onCreate") == 0) {
                native_activity_found = true;
            } else if (strcmp(name, "android_main") == 0) {
                android_main_found = true;
            } else if (strcmp(name, "JNI_OnLoad") == 0) {
                jni_onload_found = true;
            }
        }

        char line[1536];
        snprintf(line, sizeof(line),
                "%s %s\n"
                "  owner=%s\n"
                "  owner gate=%s\n"
                "  relative offset=0x%llx\n"
                "  invoked=NO\n",
                owned_by_game ? "[FOUND GAME]" : "[FOUND DEPENDENCY]",
                name,
                described ? info.dli_fname : "UNKNOWN",
                owned_by_game ? "PASS" : "REJECT",
                (unsigned long long) offset);
        probe_append(report, line);
    }

    probe_append(report, "\n=== CLEANUP ===\n");
    int close_failures = 0;
    if (dlclose(game_handle) == 0) {
        probe_append(report, "[CLOSE PASS] libminecraftpe.so\n");
    } else {
        ++close_failures;
        probe_append(report, "[CLOSE FAIL] libminecraftpe.so\n");
    }

    for (int index = loaded_count - 1; index >= 0; --index) {
        if (dependency_handles[index] == NULL) {
            continue;
        }
        int close_result = dlclose(dependency_handles[index]);
        char line[512];
        snprintf(line, sizeof(line),
                "%s %s\n",
                close_result == 0 ? "[CLOSE PASS]" : "[CLOSE FAIL]",
                PROBE_DEPENDENCIES[index]);
        probe_append(report, line);
        if (close_result != 0) {
            ++close_failures;
        }
    }

    const char* verdict;
    if (close_failures != 0) {
        verdict = "ENTRYPOINTS PROBED WITH CLEANUP FAILURE";
    } else if (native_activity_found || android_main_found) {
        verdict = "READY FOR STAGE 4 HOST BOOTSTRAP DESIGN";
    } else if (jni_onload_found) {
        verdict = "JNI BRIDGE FOUND; NATIVE ACTIVITY ENTRYPOINT NOT EXPORTED";
    } else {
        verdict = "CANONICAL GAME ENTRYPOINTS HIDDEN; ELF EXPORT INVENTORY REQUIRED";
    }

    char summary[2048];
    snprintf(summary, sizeof(summary),
            "\n=== PROBE VERDICT ===\n"
            "Symbols tested: %d\n"
            "Symbols resolved anywhere: %d\n"
            "Symbols owned by libminecraftpe.so: %d\n"
            "JNI_OnLoad owned by Minecraft: %s\n"
            "ANativeActivity_onCreate owned by Minecraft: %s\n"
            "android_main owned by Minecraft: %s\n"
            "Explicit symbols invoked: NO\n"
            "Close failures: %d\n"
            "Probe verdict: %s",
            symbol_count,
            resolved_count,
            game_owned_count,
            jni_onload_found ? "YES" : "NO",
            native_activity_found ? "YES" : "NO",
            android_main_found ? "YES" : "NO",
            close_failures,
            verdict);
    probe_append(report, summary);

    __android_log_print(
            ANDROID_LOG_INFO,
            PROBE_LOG_TAG,
            "Stage 3.6 finished: resolved=%d gameOwned=%d closeFailures=%d",
            resolved_count,
            game_owned_count,
            close_failures);

    jstring result = (*env)->NewStringUTF(env, report);
    free(report);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_balzikz_mathclient_LinkerBridge_nativeRunMinecraftSymbolProbe(
        JNIEnv* env,
        jclass clazz,
        jstring runtime_directory) {
    (void) clazz;
    if (runtime_directory == NULL) {
        return (*env)->NewStringUTF(env,
                "MATH STAGE 3.6\nRuntime directory is null.");
    }

    const char* directory = (*env)->GetStringUTFChars(
            env,
            runtime_directory,
            NULL);
    if (directory == NULL) {
        return (*env)->NewStringUTF(env,
                "MATH STAGE 3.6\nCannot read runtime directory.");
    }

    jstring result = probe_run(env, directory);
    (*env)->ReleaseStringUTFChars(env, runtime_directory, directory);
    return result;
}
