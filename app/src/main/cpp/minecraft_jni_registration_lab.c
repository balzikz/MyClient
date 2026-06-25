#define _GNU_SOURCE

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <limits.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define REPORT_CAPACITY (64 * 1024)
#define DEPENDENCY_COUNT 8
#define SHELL_CLASS_COUNT 14

static const char* LOG_TAG = "MATH-MC-JNI";
static const char* GAME_LIBRARY = "libminecraftpe.so";
static const char* JOURNAL_NAME = "stage-3.8-journal.txt";

static const char* DEPENDENCIES[DEPENDENCY_COUNT] = {
        "libc++_shared.so",
        "libfmod.so",
        "libHttpClient.Android.so",
        "libmaesdk.so",
        "libPlayFabMultiplayer.so",
        "libMediaDecoders_Android.so",
        "libconscrypt_jni.so",
        "libmcfix.so",
};

static const char* SHELL_CLASSES[SHELL_CLASS_COUNT] = {
        "com/mojang/minecraftpe/AppExitInfoHelper",
        "com/mojang/minecraftpe/BatteryMonitor",
        "com/mojang/minecraftpe/CrashManager",
        "com/mojang/minecraftpe/FilePickerManager",
        "com/mojang/minecraftpe/MainActivity",
        "com/mojang/minecraftpe/NetworkMonitor",
        "com/mojang/minecraftpe/NotificationListenerService",
        "com/mojang/minecraftpe/ThermalMonitor",
        "com/mojang/minecraftpe/WorldRecovery",
        "com/mojang/minecraftpe/Webview/MinecraftWebview",
        "com/mojang/minecraftpe/input/JellyBeanDeviceManager",
        "com/mojang/minecraftpe/store/NativeStoreListener",
        "com/mojang/minecraftpe/store/Product",
        "com/mojang/minecraftpe/store/Purchase",
};

static void* retained_dependencies[DEPENDENCY_COUNT];
static void* retained_game;
static bool attempted;
static jint last_result = JNI_ERR;

static void append_text(char* report, const char* text) {
    size_t used = strnlen(report, REPORT_CAPACITY);
    if (used >= REPORT_CAPACITY - 1) return;
    snprintf(report + used, REPORT_CAPACITY - used, "%s", text);
}

static const char* base_name(const char* path) {
    if (path == NULL) return "";
    const char* slash = strrchr(path, '/');
    return slash == NULL ? path : slash + 1;
}

static bool resolve_file(
        const char* directory,
        const char* name,
        char* resolved,
        long long* size) {
    char candidate[PATH_MAX];
    snprintf(candidate, sizeof(candidate), "%s/%s", directory, name);
    if (realpath(candidate, resolved) == NULL) return false;

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

    if (size != NULL) *size = (long long) info.st_size;
    return true;
}

static void write_all(int descriptor, const char* text) {
    size_t length = strlen(text);
    size_t offset = 0;
    while (offset < length) {
        ssize_t written = write(descriptor, text + offset, length - offset);
        if (written <= 0) return;
        offset += (size_t) written;
    }
}

static void write_journal(
        const char* directory,
        const char* status,
        const char* detail) {
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/%s", directory, JOURNAL_NAME);
    int descriptor = open(path, O_CREAT | O_TRUNC | O_WRONLY, 0600);
    if (descriptor < 0) return;

    char content[4096];
    snprintf(content, sizeof(content),
            "stage=3.8\nstatus=%s\ndetail=%s\njni_onload_invoked=%s\n",
            status,
            detail == NULL ? "NONE" : detail,
            attempted ? "YES" : "NO");
    write_all(descriptor, content);
    fsync(descriptor);
    close(descriptor);
}

static void close_local(void* game, void** dependencies, int count) {
    if (game != NULL) dlclose(game);
    for (int index = count - 1; index >= 0; --index) {
        if (dependencies[index] != NULL) {
            dlclose(dependencies[index]);
            dependencies[index] = NULL;
        }
    }
}

static void capture_exception(JNIEnv* env, char* output, size_t capacity) {
    if (!(*env)->ExceptionCheck(env)) {
        snprintf(output, capacity, "NONE");
        return;
    }

    jthrowable throwable = (*env)->ExceptionOccurred(env);
    (*env)->ExceptionClear(env);
    if (throwable == NULL) {
        snprintf(output, capacity, "PENDING EXCEPTION (NO OBJECT)");
        return;
    }

    jclass type = (*env)->GetObjectClass(env, throwable);
    if (type == NULL || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        snprintf(output, capacity, "PENDING EXCEPTION (NO CLASS)");
        (*env)->DeleteLocalRef(env, throwable);
        return;
    }

    jmethodID to_string = (*env)->GetMethodID(
            env,
            type,
            "toString",
            "()Ljava/lang/String;");
    if (to_string == NULL || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        snprintf(output, capacity, "PENDING EXCEPTION (NO toString)");
    } else {
        jstring text = (jstring) (*env)->CallObjectMethod(env, throwable, to_string);
        if (text == NULL || (*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            snprintf(output, capacity, "PENDING EXCEPTION (toString FAILED)");
        } else {
            const char* utf = (*env)->GetStringUTFChars(env, text, NULL);
            if (utf == NULL) {
                (*env)->ExceptionClear(env);
                snprintf(output, capacity, "PENDING EXCEPTION (UTF FAILED)");
            } else {
                snprintf(output, capacity, "%s", utf);
                (*env)->ReleaseStringUTFChars(env, text, utf);
            }
            (*env)->DeleteLocalRef(env, text);
        }
    }

    (*env)->DeleteLocalRef(env, type);
    (*env)->DeleteLocalRef(env, throwable);
}

static bool valid_jni_version(jint value) {
    return value == JNI_VERSION_1_1
            || value == JNI_VERSION_1_2
            || value == JNI_VERSION_1_4
            || value == JNI_VERSION_1_6;
}

static bool preflight_shell(JNIEnv* env, char* report) {
    append_text(report, "=== SHELL CLASS PREFLIGHT ===\n");
    bool ready = true;
    for (int index = 0; index < SHELL_CLASS_COUNT; ++index) {
        jclass type = (*env)->FindClass(env, SHELL_CLASSES[index]);
        if (type == NULL || (*env)->ExceptionCheck(env)) {
            char exception[1024];
            capture_exception(env, exception, sizeof(exception));
            char line[1536];
            snprintf(line, sizeof(line),
                    "[FAIL] %s\n  exception=%s\n",
                    SHELL_CLASSES[index],
                    exception);
            append_text(report, line);
            ready = false;
        } else {
            char line[512];
            snprintf(line, sizeof(line), "[PASS] %s\n", SHELL_CLASSES[index]);
            append_text(report, line);
            (*env)->DeleteLocalRef(env, type);
        }
    }
    return ready;
}

static jstring run_lab(JNIEnv* env, const char* requested_directory) {
    char* report = (char*) calloc(REPORT_CAPACITY, 1);
    if (report == NULL) {
        return (*env)->NewStringUTF(env, "MATH STAGE 3.8\nAllocation failed.");
    }

    append_text(report,
            "MATH BEDROCK JNI REGISTRATION LAB\n"
            "Stage: 3.8\n"
            "Process: :jni_registration\n"
            "Action: MANUAL JNI_OnLoad\n"
            "GameActivity instance created: NO\n"
            "Lifecycle invoked: NO\n"
            "Registered methods invoked: NO\n"
            "Unload after registration: FORBIDDEN\n\n");

    if (attempted) {
        char line[512];
        snprintf(line, sizeof(line),
                "JNI_OnLoad already attempted in this process.\n"
                "Previous return=0x%x\n"
                "Verdict: RESTART PROCESS BEFORE RETRY",
                (unsigned int) last_result);
        append_text(report, line);
        jstring result = (*env)->NewStringUTF(env, report);
        free(report);
        return result;
    }

    char directory[PATH_MAX];
    if (realpath(requested_directory, directory) == NULL) {
        append_text(report, "Directory gate: BLOCK\nVerdict: INVALID DIRECTORY");
        jstring result = (*env)->NewStringUTF(env, report);
        free(report);
        return result;
    }

    char line[PATH_MAX * 2 + 128];
    snprintf(line, sizeof(line),
            "Runtime directory: %s\nCanonical path: %s\n\n",
            requested_directory,
            directory);
    append_text(report, line);

    if (!preflight_shell(env, report)) {
        write_journal(directory, "SHELL_PREFLIGHT_FAIL", "required classes unavailable");
        append_text(report,
                "\nJNI_OnLoad invoked: NO\n"
                "Verdict: FIX SHELL BEFORE INVOCATION");
        jstring result = (*env)->NewStringUTF(env, report);
        free(report);
        return result;
    }

    char dependency_paths[DEPENDENCY_COUNT][PATH_MAX];
    void* local_dependencies[DEPENDENCY_COUNT];
    memset(dependency_paths, 0, sizeof(dependency_paths));
    memset(local_dependencies, 0, sizeof(local_dependencies));

    char game_path[PATH_MAX];
    long long game_size = 0;

    append_text(report, "\n=== PATH GATE ===\n");
    for (int index = 0; index < DEPENDENCY_COUNT; ++index) {
        long long size = 0;
        if (!resolve_file(directory, DEPENDENCIES[index], dependency_paths[index], &size)) {
            snprintf(line, sizeof(line), "[BLOCK] %s\n", DEPENDENCIES[index]);
            append_text(report, line);
            write_journal(directory, "PATH_GATE_FAIL", DEPENDENCIES[index]);
            jstring result = (*env)->NewStringUTF(env, report);
            free(report);
            return result;
        }
        snprintf(line, sizeof(line), "[READY] %s | %lld bytes\n", DEPENDENCIES[index], size);
        append_text(report, line);
    }

    if (!resolve_file(directory, GAME_LIBRARY, game_path, &game_size)) {
        append_text(report, "[BLOCK] libminecraftpe.so\n");
        write_journal(directory, "PATH_GATE_FAIL", GAME_LIBRARY);
        jstring result = (*env)->NewStringUTF(env, report);
        free(report);
        return result;
    }
    snprintf(line, sizeof(line), "[READY] %s | %lld bytes\n", GAME_LIBRARY, game_size);
    append_text(report, line);

    append_text(report, "\n=== GLOBAL DEPENDENCY PRELOAD ===\n");
    int loaded_count = 0;
    for (int index = 0; index < DEPENDENCY_COUNT; ++index) {
        dlerror();
        local_dependencies[index] = dlopen(dependency_paths[index], RTLD_NOW | RTLD_GLOBAL);
        if (local_dependencies[index] == NULL) {
            const char* error = dlerror();
            snprintf(line, sizeof(line),
                    "[FAIL] %s\nlinker error=%s\n",
                    DEPENDENCIES[index],
                    error == NULL ? "unknown" : error);
            append_text(report, line);
            close_local(NULL, local_dependencies, loaded_count);
            write_journal(directory, "DEPENDENCY_DLOPEN_FAIL", DEPENDENCIES[index]);
            jstring result = (*env)->NewStringUTF(env, report);
            free(report);
            return result;
        }
        ++loaded_count;
        snprintf(line, sizeof(line), "[PASS] %s\n", DEPENDENCIES[index]);
        append_text(report, line);
    }

    write_journal(directory, "BEFORE_MINECRAFT_DLOPEN", "constructors may execute next");
    dlerror();
    void* local_game = dlopen(game_path, RTLD_NOW | RTLD_LOCAL);
    if (local_game == NULL) {
        const char* error = dlerror();
        snprintf(line, sizeof(line),
                "\n=== MINECRAFT LOAD ===\ndlopen=FAIL\nlinker error=%s\n",
                error == NULL ? "unknown" : error);
        append_text(report, line);
        close_local(NULL, local_dependencies, loaded_count);
        write_journal(directory, "MINECRAFT_DLOPEN_FAIL", error == NULL ? "unknown" : error);
        jstring result = (*env)->NewStringUTF(env, report);
        free(report);
        return result;
    }
    append_text(report, "\n=== MINECRAFT LOAD ===\ndlopen=PASS\n");

    dlerror();
    void* symbol = dlsym(local_game, "JNI_OnLoad");
    const char* symbol_error = dlerror();
    Dl_info info;
    memset(&info, 0, sizeof(info));
    bool described = symbol != NULL
            && symbol_error == NULL
            && dladdr(symbol, &info) != 0
            && info.dli_fname != NULL;
    bool game_owned = described && strcmp(base_name(info.dli_fname), GAME_LIBRARY) == 0;
    if (!game_owned) {
        snprintf(line, sizeof(line),
                "JNI_OnLoad gate=FAIL\nowner=%s\nerror=%s\n",
                described ? info.dli_fname : "UNKNOWN",
                symbol_error == NULL ? "NONE" : symbol_error);
        append_text(report, line);
        close_local(local_game, local_dependencies, loaded_count);
        write_journal(directory, "JNI_SYMBOL_GATE_FAIL", "JNI_OnLoad missing or wrong owner");
        jstring result = (*env)->NewStringUTF(env, report);
        free(report);
        return result;
    }

    JavaVM* vm = NULL;
    if ((*env)->GetJavaVM(env, &vm) != JNI_OK || vm == NULL) {
        append_text(report, "JavaVM gate=FAIL\n");
        close_local(local_game, local_dependencies, loaded_count);
        write_journal(directory, "JAVA_VM_GATE_FAIL", "GetJavaVM failed");
        jstring result = (*env)->NewStringUTF(env, report);
        free(report);
        return result;
    }

    memcpy(retained_dependencies, local_dependencies, sizeof(retained_dependencies));
    retained_game = local_game;
    attempted = true;

    append_text(report,
            "JNI_OnLoad gate=PASS\n"
            "JavaVM gate=PASS\n"
            "Handles retained before invocation=YES\n\n"
            "=== JNI_ONLOAD INVOCATION ===\n");
    write_journal(directory, "BEFORE_JNI_ONLOAD", "manual JNI_OnLoad begins next");

    typedef jint (*jni_onload_fn)(JavaVM*, void*);
    jni_onload_fn invoke = (jni_onload_fn) symbol;
    last_result = invoke(vm, NULL);

    bool had_exception = (*env)->ExceptionCheck(env);
    char exception[2048];
    capture_exception(env, exception, sizeof(exception));

    snprintf(line, sizeof(line),
            "JNI_OnLoad returned=YES\n"
            "return decimal=%d\n"
            "return hex=0x%x\n"
            "valid JNI version=%s\n"
            "pending exception=%s\n"
            "exception=%s\n"
            "GameActivity created=NO\n"
            "Lifecycle invoked=NO\n"
            "Registered methods invoked=NO\n"
            "Handles retained=%d dependencies + Minecraft\n",
            last_result,
            (unsigned int) last_result,
            valid_jni_version(last_result) ? "YES" : "NO",
            had_exception ? "YES" : "NO",
            exception,
            loaded_count);
    append_text(report, line);

    bool success = valid_jni_version(last_result) && !had_exception;
    write_journal(
            directory,
            success ? "COMPLETE_PASS" : "COMPLETE_FAIL",
            success ? "valid JNI version and no pending exception"
                    : "invalid JNI version or pending exception");

    append_text(report, "\n=== JNI REGISTRATION VERDICT ===\n");
    append_text(report, success
            ? "JNI registration: PASS\nVerdict: READY FOR STAGE 3.9 GAMEACTIVITY HOST\n"
            : "JNI registration: FAIL\nVerdict: EXPAND SHELL OR INSPECT JNI_ONLOAD FAILURE\n");
    append_text(report, "Unload policy: RETAIN UNTIL PROCESS DEATH\n");

    __android_log_print(
            ANDROID_LOG_INFO,
            LOG_TAG,
            "Stage 3.8 return=0x%x exception=%s",
            (unsigned int) last_result,
            had_exception ? "YES" : "NO");

    jstring result = (*env)->NewStringUTF(env, report);
    free(report);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_balzikz_mathclient_LinkerBridge_nativeRunMinecraftJniRegistration(
        JNIEnv* env,
        jclass clazz,
        jstring runtime_directory) {
    (void) clazz;
    if (runtime_directory == NULL) {
        return (*env)->NewStringUTF(env, "MATH STAGE 3.8\nRuntime directory is null.");
    }

    const char* directory = (*env)->GetStringUTFChars(env, runtime_directory, NULL);
    if (directory == NULL) {
        return (*env)->NewStringUTF(env, "MATH STAGE 3.8\nCannot read runtime directory.");
    }

    jstring result = run_lab(env, directory);
    (*env)->ReleaseStringUTFChars(env, runtime_directory, directory);
    return result;
}
