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

#define JNI_LAB_REPORT_CAPACITY (64 * 1024)
#define JNI_LAB_DEPENDENCY_COUNT 8
#define JNI_LAB_CLASS_COUNT 14

static const char* JNI_LAB_LOG_TAG = "MATH-MC-JNI";
static const char* JNI_LAB_GAME_LIBRARY = "libminecraftpe.so";
static const char* JNI_LAB_JOURNAL = "stage-3.8-journal.txt";

static const char* JNI_LAB_DEPENDENCIES[JNI_LAB_DEPENDENCY_COUNT] = {
        "libc++_shared.so",
        "libfmod.so",
        "libHttpClient.Android.so",
        "libmaesdk.so",
        "libPlayFabMultiplayer.so",
        "libMediaDecoders_Android.so",
        "libconscrypt_jni.so",
        "libmcfix.so",
};

static const char* JNI_LAB_CLASSES[JNI_LAB_CLASS_COUNT] = {
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

static void* g_dependency_handles[JNI_LAB_DEPENDENCY_COUNT];
static void* g_game_handle;
static bool g_jni_onload_attempted;
static jint g_jni_onload_result = JNI_ERR;

static void jni_lab_append(char* report, const char* text) {
    size_t used = strnlen(report, JNI_LAB_REPORT_CAPACITY);
    if (used >= JNI_LAB_REPORT_CAPACITY - 1) {
        return;
    }
    snprintf(report + used, JNI_LAB_REPORT_CAPACITY - used, "%s", text);
}

static const char* jni_lab_base_name(const char* path) {
    if (path == NULL) {
        return "";
    }
    const char* slash = strrchr(path, '/');
    return slash == NULL ? path : slash + 1;
}

static bool jni_lab_resolve_file(
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

static void jni_lab_write_all(int descriptor, const char* text) {
    size_t length = strlen(text);
    size_t offset = 0;
    while (offset < length) {
        ssize_t written = write(descriptor, text + offset, length - offset);
        if (written <= 0) {
            return;
        }
        offset += (size_t) written;
    }
}

static void jni_lab_write_journal(
        const char* directory,
        const char* status,
        const char* detail) {
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/%s", directory, JNI_LAB_JOURNAL);

    int descriptor = open(path, O_CREAT | O_TRUNC | O_WRONLY, 0600);
    if (descriptor < 0) {
        return;
    }

    char content[4096];
    snprintf(content, sizeof(content),
            "stage=3.8\n"
            "status=%s\n"
            "detail=%s\n"
            "jni_onload_invoked=%s\n",
            status,
            detail == NULL ? "NONE" : detail,
            g_jni_onload_attempted ? "YES" : "NO");
    jni_lab_write_all(descriptor, content);
    fsync(descriptor);
    close(descriptor);
}

static void jni_lab_cleanup_local(
        void* game_handle,
        void** dependency_handles,
        int dependency_count) {
    if (game_handle != NULL) {
        dlclose(game_handle);
    }
    for (int index = dependency_count - 1; index >= 0; --index) {
        if (dependency_handles[index] != NULL) {
            dlclose(dependency_handles[index]);
            dependency_handles[index] = NULL;
        }
    }
}

static void jni_lab_capture_exception(
        JNIEnv* env,
        char* destination,
        size_t capacity) {
    if (!(*env)->ExceptionCheck(env)) {
        snprintf(destination, capacity, "NONE");
        return;
    }

    jthrowable throwable = (*env)->ExceptionOccurred(env);
    (*env)->ExceptionClear(env);
    if (throwable == NULL) {
        snprintf(destination, capacity, "PENDING EXCEPTION (OBJECT UNAVAILABLE)");
        return;
    }

    jclass throwable_class = (*env)->GetObjectClass(env, throwable);
    if (throwable_class == NULL || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        snprintf(destination, capacity, "PENDING EXCEPTION (CLASS UNAVAILABLE)");
        (*env)->DeleteLocalRef(env, throwable);
        return;
    }

    jmethodID to_string = (*env)->GetMethodID(
            env,
            throwable_class,
            "toString",
            "()Ljava/lang/String;");
    if (to_string == NULL || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        snprintf(destination, capacity, "PENDING EXCEPTION (toString UNAVAILABLE)");
        (*env)->DeleteLocalRef(env, throwable_class);
        (*env)->DeleteLocalRef(env, throwable);
        return;
    }

    jstring text = (jstring) (*env)->CallObjectMethod(env, throwable, to_string);
    if (text == NULL || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        snprintf(destination, capacity, "PENDING EXCEPTION (toString FAILED)");
    } else {
        const char* utf = (*env)->GetStringUTFChars(env, text, NULL);
        if (utf == NULL) {
            (*env)->ExceptionClear(env);
            snprintf(destination, capacity, "PENDING EXCEPTION (UTF CONVERSION FAILED)");
        } else {
            snprintf(destination, capacity, "%s", utf);
            (*env)->ReleaseStringUTFChars(env, text, utf);
        }
        (*env)->DeleteLocalRef(env, text);
    }

    (*env)->DeleteLocalRef(env, throwable_class);
    (*env)->DeleteLocalRef(env, throwable);
}

static bool jni_lab_valid_version(jint value) {
    return value == JNI_VERSION_1_1
            || value == JNI_VERSION_1_2
            || value == JNI_VERSION_1_4
            || value == JNI_VERSION_1_6;
}

static bool jni_lab_preflight_classes(JNIEnv* env, char* report) {
    jni_lab_append(report, "=== SHELL CLASS PREFLIGHT ===\n");
    bool ready = true;
    for (int index = 0; index < JNI_LAB_CLASS_COUNT; ++index) {
        const char* class_name = JNI_LAB_CLASSES[index];
        jclass type = (*env)->FindClass(env, class_name);
        if (type == NULL || (*env)->ExceptionCheck(env)) {
            char exception[1024];
            jni_lab_capture_exception(env, exception, sizeof(exception));
            char line[1536];
            snprintf(line, sizeof(line),
                    "[FAIL] %s\n  exception=%s\n",
                    class_name,
                    exception);
            jni_lab_append(report, line);
            ready = false;
        } else {
            char line[512];
            snprintf(line, sizeof(line), "[PASS] %s\n", class_name);
            jni_lab_append(report, line);
            (*env)->DeleteLocalRef(env, type);
        }
    }
    return ready;
}

static jstring jni_lab_run(JNIEnv* env, const char* requested_directory) {
    char* report = (char*) calloc(JNI_LAB_REPORT_CAPACITY, 1);
    if (report == NULL) {
        return (*env)->NewStringUTF(env, "MATH STAGE 3.8\nAllocation failed.");
    }

    jni_lab_append(report,
            "MATH BEDROCK JNI REGISTRATION LAB\n"
            "Stage: 3.8\n"
            "Process policy: ISOLATED SECONDARY APP PROCESS (:jni_registration)\n"
            "Bridge: DEDICATED C LIBRARY (NO C++ RUNTIME)\n"
            "Dependencies: RTLD_NOW | RTLD_GLOBAL\n"
            "Minecraft: RTLD_NOW | RTLD_LOCAL\n"
            "Action: MANUAL JNI_OnLoad INVOCATION\n"
            "Game Activity instance created: NO\n"
            "Lifecycle callbacks invoked: NO\n"
            "Registered native methods invoked: NO\n"
            "Unload after JNI registration: FORBIDDEN\n\n");

    if (g_jni_onload_attempted) {
        char line[512];
        snprintf(line, sizeof(line),
                "JNI_OnLoad already attempted in this process.\n"
                "Previous return=0x%x\n"
                "Verdict: RESTART :jni_registration PROCESS BEFORE RETRY",
                (unsigned int) g_jni_onload_result);
        jni_lab_append(report, line);
        jstring result = (*env)->NewStringUTF(env, report);
        free(report);
        return result;
    }

    char directory[PATH_MAX];
    if (realpath(requested_directory, directory) == NULL) {
        jni_lab_append(report,
                "Directory gate: BLOCK\n"
                "Verdict: INVALID RUNTIME DIRECTORY");
        jstring result = (*env)->NewStringUTF(env, report);
        free(report);
        return result;
    }

    char directory_line[PATH_MAX * 2 + 128];
    snprintf(directory_line, sizeof(directory_line),
            "Runtime directory: %s\nCanonical path: %s\n\n",
            requested_directory,
            directory);
    jni_lab_append(report, directory_line);

    if (!jni_lab_preflight_classes(env, report)) {
        jni_lab_write_journal(directory, "SHELL_PREFLIGHT_FAIL", "one or more JNI shell classes are unavailable");
        jni_lab_append(report,
                "\nShell preflight verdict: BLOCK\n"
                "JNI_OnLoad invoked: NO\n"
                "Verdict: FIX JNI SHELL BEFORE INVOCATION");
        jstring result = (*env)->NewStringUTF(env, report);
        free(report);
        return result;
    }
    jni_lab_write_journal(directory, "SHELL_PREFLIGHT_PASS", "all required shell classes resolved");

    char dependency_paths[JNI_LAB_DEPENDENCY_COUNT][PATH_MAX];
    void* local_dependency_handles[JNI_LAB_DEPENDENCY_COUNT];
    memset(dependency_paths, 0, sizeof(dependency_paths));
    memset(local_dependency_handles, 0, sizeof(local_dependency_handles));

    char game_path[PATH_MAX];
    long long game_size = 0;

    jni_lab_append(report, "\n=== PATH GATE ===\n");
    for (int index = 0; index < JNI_LAB_DEPENDENCY_COUNT; ++index) {
        long long size = 0;
        if (!jni_lab_resolve_file(
                directory,
                JNI_LAB_DEPENDENCIES[index],
                dependency_paths[index],
                &size)) {
            char line[512];
            snprintf(line, sizeof(line), "[BLOCK] %s\n", JNI_LAB_DEPENDENCIES[index]);
            jni_lab_append(report, line);
            jni_lab_cleanup_local(NULL, local_dependency_handles, index);
            jni_lab_write_journal(directory, "PATH_GATE_FAIL", JNI_LAB_DEPENDENCIES[index]);
            jstring result = (*env)->NewStringUTF(env, report);
            free(report);
            return result;
        }
        char line[512];
        snprintf(line, sizeof(line),
                "[READY] %s | %lld bytes\n",
                JNI_LAB_DEPENDENCIES[index],
                size);
        jni_lab_append(report, line);
    }

    if (!jni_lab_resolve_file(directory, JNI_LAB_GAME_LIBRARY, game_path, &game_size)) {
        jni_lab_append(report, "[BLOCK] libminecraftpe.so\n");
        jni_lab_write_journal(directory, "PATH_GATE_FAIL", JNI_LAB_GAME_LIBRARY);
        jstring result = (*env)->NewStringUTF(env, report);
        free(report);
        return result;
    }
    char game_line[512];
    snprintf(game_line, sizeof(game_line),
            "[READY] %s | %lld bytes\n",
            JNI_LAB_GAME_LIBRARY,
            game_size);
    jni_lab_append(report, game_line);

    jni_lab_append(report, "\n=== GLOBAL DEPENDENCY PRELOAD ===\n");
    int loaded_count = 0;
    for (int index = 0; index < JNI_LAB_DEPENDENCY_COUNT; ++index) {
        dlerror();
        local_dependency_handles[index] = dlopen(
                dependency_paths[index],
                RTLD_NOW | RTLD_GLOBAL);
        if (local_dependency_handles[index] == NULL) {
            const char* error = dlerror();
            char line[4096];
            snprintf(line, sizeof(line),
                    "[FAIL] %s\nlinker error=%s\n",
                    JNI_LAB_DEPENDENCIES[index],
                    error == NULL ? "unknown" : error);
            jni_lab_append(report, line);
            jni_lab_cleanup_local(NULL, local_dependency_handles, loaded_count);
            jni_lab_write_journal(directory, "DEPENDENCY_DLOPEN_FAIL", JNI_LAB_DEPENDENCIES[index]);
            jstring result = (*env)->NewStringUTF(env, report);
            free(report);
            return result;
        }
        ++loaded_count;
        char line[256];
        snprintf(line, sizeof(line), "[PASS] %s\n", JNI_LAB_DEPENDENCIES[index]);
        jni_lab_append(report, line);
    }

    jni_lab_write_journal(directory, "BEFORE_MINECRAFT_DLOPEN", "constructors may execute next");
    dlerror();
    void* local_game_handle = dlopen(game_path, RTLD_NOW | RTLD_LOCAL);
    if (local_game_handle == NULL) {
        const char* error = dlerror();
        char line[4096];
        snprintf(line, sizeof(line),
                "\n=== MINECRAFT LOAD ===\n"
                "dlopen=FAIL\n"
                "linker error=%s\n",
                error == NULL ? "unknown" : error);
        jni_lab_append(report, line);
        jni_lab_cleanup_local(NULL, local_dependency_handles, loaded_count);
        jni_lab_write_journal(directory, "MINECRAFT_DLOPEN_FAIL", error == NULL ? "unknown" : error);
        jstring result = (*env)->NewStringUTF(env, report);
        free(report);
        return result;
    }
    jni_lab_append(report, "\n=== MINECRAFT LOAD ===\ndlopen=PASS\n");

    dlerror();
    void* raw_jni_onload = dlsym(local_game_handle, "JNI_OnLoad");
    const char* symbol_error = dlerror();
    Dl_info symbol_info;
    memset(&symbol_info, 0, sizeof(symbol_info));
    bool described = raw_jni_onload != NULL
            && symbol_error == NULL
            && dladdr(raw_jni_onload, &symbol_info) != 0
            && symbol_info.dli_fname != NULL;
    bool game_owned = described
            && strcmp(jni_lab_base_name(symbol_info.dli_fname), JNI_LAB_GAME_LIBRARY) == 0;

    if (!game_owned) {
        char line[4096];
        snprintf(line, sizeof(line),
                "JNI_OnLoad symbol gate=FAIL\n"
                "owner=%s\n"
                "symbol error=%s\n",
                described ? symbol_info.dli_fname : "UNKNOWN",
                symbol_error == NULL ? "NONE" : symbol_error);
        jni_lab_append(report, line);
        jni_lab_cleanup_local(local_game_handle, local_dependency_handles, loaded_count);
        jni_lab_write_journal(directory, "JNI_SYMBOL_GATE_FAIL", "JNI_OnLoad is missing or not owned by libminecraftpe.so");
        jstring result = (*env)->NewStringUTF(env, report);
        free(report);
        return result;
    }

    JavaVM* vm = NULL;
    if ((*env)->GetJavaVM(env, &vm) != JNI_OK || vm == NULL) {
        jni_lab_append(report, "JavaVM gate=FAIL\n");
        jni_lab_cleanup_local(local_game_handle, local_dependency_handles, loaded_count);
        jni_lab_write_journal(directory, "JAVA_VM_GATE_FAIL", "GetJavaVM failed");
        jstring result = (*env)->NewStringUTF(env, report);
        free(report);
        return result;
    }

    memcpy(g_dependency_handles, local_dependency_handles, sizeof(g_dependency_handles));
    g_game_handle = local_game_handle;
    g_jni_onload_attempted = true;

    jni_lab_append(report,
            "JNI_OnLoad symbol gate=PASS\n"
            "JavaVM gate=PASS\n"
            "Handles retained before invocation=YES\n\n"
            "=== JNI_ONLOAD INVOCATION ===\n");
    jni_lab_write_journal(directory, "BEFORE_JNI_ONLOAD", "manual JNI_OnLoad call begins next; process may abort");

    typedef jint (*jni_onload_function)(JavaVM*, void*);
    jni_onload_function invoke = (jni_onload_function) raw_jni_onload;
    g_jni_onload_result = invoke(vm, NULL);

    char exception[2048];
    bool pending_exception = (*env)->ExceptionCheck(env);
    jni_lab_capture_exception(env, exception, sizeof(exception));

    char invocation_line[4096];
    snprintf(invocation_line, sizeof(invocation_line),
            "JNI_OnLoad returned=YES\n"
            "return decimal=%d\n"
            "return hex=0x%x\n"
            "valid JNI version=%s\n"
            "pending exception after return=%s\n"
            "exception=%s\n"
            "Game Activity instance created=NO\n"
            "Lifecycle callbacks invoked=NO\n"
            "Registered native methods invoked=NO\n"
            "Minecraft handle retained=YES\n"
            "Dependency handles retained=%d/%d\n",
            g_jni_onload_result,
            (unsigned int) g_jni_onload_result,
            jni_lab_valid_version(g_jni_onload_result) ? "YES" : "NO",
            pending_exception ? "YES" : "NO",
            exception,
            loaded_count,
            JNI_LAB_DEPENDENCY_COUNT);
    jni_lab_append(report, invocation_line);

    bool success = jni_lab_valid_version(g_jni_onload_result) && !pending_exception;
    jni_lab_write_journal(
            directory,
            success ? "COMPLETE_PASS" : "COMPLETE_FAIL",
            success ? "JNI_OnLoad returned a valid version without a pending exception"
                    : "JNI_OnLoad returned an invalid version or left a pending exception");

    jni_lab_append(report, "\n=== JNI REGISTRATION VERDICT ===\n");
    if (success) {
        jni_lab_append(report,
                "JNI registration: PASS\n"
                "Unload policy: RETAIN UNTIL PROCESS DEATH\n"
                "Verdict: READY FOR STAGE 3.9 GAMEACTIVITY HOST\n");
    } else {
        jni_lab_append(report,
                "JNI registration: FAIL\n"
                "Unload policy: RETAIN UNTIL PROCESS DEATH\n"
                "Verdict: EXPAND JNI SHELL OR INSPECT JNI_ONLOAD FAILURE\n");
    }

    __android_log_print(
            ANDROID_LOG_INFO,
            JNI_LAB_LOG_TAG,
            "Stage 3.8 returned=0x%x exception=%s",
            (unsigned int) g_jni_onload_result,
            pending_exception ? "YES" : "NO");

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
        return (*env)->NewStringUTF(env,
                "MATH STAGE 3.8\nRuntime directory is null.");
    }

    const char* directory = (*env)->GetStringUTFChars(env, runtime_directory, NULL);
    if (directory == NULL) {
        return (*env)->NewStringUTF(env,
                "MATH STAGE 3.8\nCannot read runtime directory.");
    }

    jstring result = jni_lab_run(env, directory);
    (*env)->ReleaseStringUTFChars(env, runtime_directory, directory);
    return result;
}
