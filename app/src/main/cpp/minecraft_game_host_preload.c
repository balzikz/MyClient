#define _GNU_SOURCE
#include <jni.h>
#include <dlfcn.h>
#include <limits.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define DEP_COUNT 8
#define REPORT_CAP 8192

static const char* DEPS[DEP_COUNT] = {
    "libc++_shared.so", "libfmod.so", "libHttpClient.Android.so", "libmaesdk.so",
    "libPlayFabMultiplayer.so", "libMediaDecoders_Android.so", "libconscrypt_jni.so", "libmcfix.so"
};

static void* HANDLES[DEP_COUNT];
static bool READY;

static void append(char* out, const char* text) {
    size_t used = strnlen(out, REPORT_CAP);
    if (used < REPORT_CAP - 1) snprintf(out + used, REPORT_CAP - used, "%s", text);
}

static bool file_ok(const char* dir, const char* name, char* path) {
    char candidate[PATH_MAX];
    snprintf(candidate, sizeof(candidate), "%s/%s", dir, name);
    if (realpath(candidate, path) == NULL) return false;
    size_t n = strlen(dir);
    if (strncmp(path, dir, n) != 0 || path[n] != '/') return false;
    struct stat st;
    return stat(path, &st) == 0 && S_ISREG(st.st_mode) && access(path, R_OK) == 0;
}

JNIEXPORT jstring JNICALL
Java_com_balzikz_mathclient_LinkerBridge_nativePrepareMinecraftHost(
        JNIEnv* env, jclass type, jstring runtime_dir) {
    (void) type;
    char out[REPORT_CAP] = {0};
    append(out, "MATH GAME HOST PRELOAD\nStage: 3.9\n");
    if (READY) {
        append(out, "Dependencies: ALREADY READY\nVerdict: READY FOR SYSTEM LOAD");
        return (*env)->NewStringUTF(env, out);
    }
    if (runtime_dir == NULL) {
        append(out, "Verdict: NULL RUNTIME DIRECTORY");
        return (*env)->NewStringUTF(env, out);
    }
    const char* requested = (*env)->GetStringUTFChars(env, runtime_dir, NULL);
    if (requested == NULL) return (*env)->NewStringUTF(env, "MATH GAME HOST PRELOAD\nUTF ERROR");

    char dir[PATH_MAX];
    if (realpath(requested, dir) == NULL) {
        (*env)->ReleaseStringUTFChars(env, runtime_dir, requested);
        append(out, "Verdict: INVALID RUNTIME DIRECTORY");
        return (*env)->NewStringUTF(env, out);
    }

    char paths[DEP_COUNT][PATH_MAX];
    for (int i = 0; i < DEP_COUNT; ++i) {
        if (!file_ok(dir, DEPS[i], paths[i])) {
            char line[256];
            snprintf(line, sizeof(line), "[MISSING] %s\nVerdict: PATH GATE FAILED", DEPS[i]);
            append(out, line);
            (*env)->ReleaseStringUTFChars(env, runtime_dir, requested);
            return (*env)->NewStringUTF(env, out);
        }
    }

    char game_path[PATH_MAX];
    if (!file_ok(dir, "libminecraftpe.so", game_path)) {
        append(out, "[MISSING] libminecraftpe.so\nVerdict: PATH GATE FAILED");
        (*env)->ReleaseStringUTFChars(env, runtime_dir, requested);
        return (*env)->NewStringUTF(env, out);
    }

    for (int i = 0; i < DEP_COUNT; ++i) {
        dlerror();
        HANDLES[i] = dlopen(paths[i], RTLD_NOW | RTLD_GLOBAL);
        if (HANDLES[i] == NULL) {
            const char* error = dlerror();
            char line[1024];
            snprintf(line, sizeof(line), "[FAIL] %s\n%s\nVerdict: PRELOAD FAILED",
                    DEPS[i], error == NULL ? "unknown linker error" : error);
            append(out, line);
            for (int j = i - 1; j >= 0; --j) {
                if (HANDLES[j] != NULL) dlclose(HANDLES[j]);
                HANDLES[j] = NULL;
            }
            (*env)->ReleaseStringUTFChars(env, runtime_dir, requested);
            return (*env)->NewStringUTF(env, out);
        }
        char line[128];
        snprintf(line, sizeof(line), "[PASS] %s\n", DEPS[i]);
        append(out, line);
    }

    READY = true;
    append(out, "Dependencies retained: 8/8\nMinecraft loaded here: NO\nVerdict: READY FOR SYSTEM LOAD");
    (*env)->ReleaseStringUTFChars(env, runtime_dir, requested);
    return (*env)->NewStringUTF(env, out);
}
