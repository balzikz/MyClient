#define _GNU_SOURCE
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <time.h>
#include <ucontext.h>
#include <unistd.h>

#define TRACE_CAP 4096
#define MARKER_CAP 128
#define STACK_SCAN_WORDS 256
#define STACK_CANDIDATE_CAP 24

static char TRACE_PATH[PATH_MAX];
static char CURRENT_MARKER[MARKER_CAP] = "UNSET";
static uintptr_t MINECRAFT_START;
static uintptr_t MINECRAFT_END;
static volatile sig_atomic_t HANDLING_SIGNAL;

static size_t append_text(char* out, size_t cap, size_t pos, const char* text) {
    if (text == NULL) text = "NULL";
    while (*text != '\0' && pos + 1 < cap) out[pos++] = *text++;
    out[pos] = '\0';
    return pos;
}

static size_t append_uint(char* out, size_t cap, size_t pos, uint64_t value) {
    char digits[32];
    size_t count = 0;
    do {
        digits[count++] = (char) ('0' + (value % 10));
        value /= 10;
    } while (value != 0 && count < sizeof(digits));
    while (count > 0 && pos + 1 < cap) out[pos++] = digits[--count];
    out[pos] = '\0';
    return pos;
}

static size_t append_int(char* out, size_t cap, size_t pos, int64_t value) {
    if (value < 0) {
        pos = append_text(out, cap, pos, "-");
        return append_uint(out, cap, pos, (uint64_t) (-(value + 1)) + 1U);
    }
    return append_uint(out, cap, pos, (uint64_t) value);
}

static size_t append_hex(char* out, size_t cap, size_t pos, uintptr_t value) {
    static const char HEX[] = "0123456789abcdef";
    char digits[2 * sizeof(uintptr_t)];
    size_t count = 0;
    pos = append_text(out, cap, pos, "0x");
    do {
        digits[count++] = HEX[value & 0xFU];
        value >>= 4U;
    } while (value != 0 && count < sizeof(digits));
    while (count > 0 && pos + 1 < cap) out[pos++] = digits[--count];
    out[pos] = '\0';
    return pos;
}

static void write_all(int fd, const char* data, size_t size) {
    while (size > 0) {
        ssize_t written = write(fd, data, size);
        if (written > 0) {
            data += written;
            size -= (size_t) written;
        } else if (written < 0 && errno == EINTR) {
            continue;
        } else {
            break;
        }
    }
}

static void refresh_minecraft_range(void) {
    FILE* maps = fopen("/proc/self/maps", "r");
    if (maps == NULL) return;

    uintptr_t low = UINTPTR_MAX;
    uintptr_t high = 0;
    char line[PATH_MAX + 256];
    while (fgets(line, sizeof(line), maps) != NULL) {
        if (strstr(line, "libminecraftpe.so") == NULL) continue;
        unsigned long long start = 0;
        unsigned long long end = 0;
        if (sscanf(line, "%llx-%llx", &start, &end) == 2) {
            if ((uintptr_t) start < low) low = (uintptr_t) start;
            if ((uintptr_t) end > high) high = (uintptr_t) end;
        }
    }
    fclose(maps);

    if (low != UINTPTR_MAX && high > low) {
        MINECRAFT_START = low;
        MINECRAFT_END = high;
    }
}

static void append_normal_record(const char* event) {
    if (TRACE_PATH[0] == '\0') return;
    int fd = open(TRACE_PATH, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
    if (fd < 0) return;
    char line[768];
    int length = snprintf(line, sizeof(line),
            "\n---\nevent=%s\npid=%d\ntid=%ld\nmarker=%s\n"
            "minecraft_start=0x%llx\nminecraft_end=0x%llx\ntime=%lld\n",
            event == NULL ? "UNKNOWN" : event,
            getpid(), (long) syscall(SYS_gettid), CURRENT_MARKER,
            (unsigned long long) MINECRAFT_START,
            (unsigned long long) MINECRAFT_END,
            (long long) time(NULL));
    if (length > 0) write_all(fd, line, (size_t) length);
    close(fd);
}

static void signal_handler(int signal_number, siginfo_t* info, void* context) {
    if (HANDLING_SIGNAL) _exit(128 + signal_number);
    HANDLING_SIGNAL = 1;

    uintptr_t pc = 0;
    uintptr_t lr = 0;
    uintptr_t sp = 0;
    uintptr_t fp = 0;
#if defined(__aarch64__)
    ucontext_t* machine = (ucontext_t*) context;
    if (machine != NULL) {
        pc = (uintptr_t) machine->uc_mcontext.pc;
        sp = (uintptr_t) machine->uc_mcontext.sp;
        fp = (uintptr_t) machine->uc_mcontext.regs[29];
        lr = (uintptr_t) machine->uc_mcontext.regs[30];
    }
#endif

    int fd = -1;
    if (TRACE_PATH[0] != '\0') {
        fd = open(TRACE_PATH, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
    }
    if (fd >= 0) {
        char out[TRACE_CAP];
        size_t pos = 0;
        struct timespec now = {0, 0};
        clock_gettime(CLOCK_REALTIME, &now);

        pos = append_text(out, sizeof(out), pos, "\n=== NATIVE SIGNAL ===\nsignal=");
        pos = append_int(out, sizeof(out), pos, signal_number);
        pos = append_text(out, sizeof(out), pos, "\nsi_code=");
        pos = append_int(out, sizeof(out), pos, info == NULL ? 0 : info->si_code);
        pos = append_text(out, sizeof(out), pos, "\nsi_addr=");
        pos = append_hex(out, sizeof(out), pos,
                info == NULL ? 0 : (uintptr_t) info->si_addr);
        pos = append_text(out, sizeof(out), pos, "\npid=");
        pos = append_uint(out, sizeof(out), pos, (uint64_t) getpid());
        long tid = syscall(SYS_gettid);
        pos = append_text(out, sizeof(out), pos, "\ntid=");
        pos = append_uint(out, sizeof(out), pos, (uint64_t) tid);
        pos = append_text(out, sizeof(out), pos, "\nmarker=");
        pos = append_text(out, sizeof(out), pos, CURRENT_MARKER);
        pos = append_text(out, sizeof(out), pos, "\ntime_sec=");
        pos = append_int(out, sizeof(out), pos, now.tv_sec);
        pos = append_text(out, sizeof(out), pos, "\ntime_nsec=");
        pos = append_int(out, sizeof(out), pos, now.tv_nsec);
        pos = append_text(out, sizeof(out), pos, "\npc=");
        pos = append_hex(out, sizeof(out), pos, pc);
        pos = append_text(out, sizeof(out), pos, "\nlr=");
        pos = append_hex(out, sizeof(out), pos, lr);
        pos = append_text(out, sizeof(out), pos, "\nsp=");
        pos = append_hex(out, sizeof(out), pos, sp);
        pos = append_text(out, sizeof(out), pos, "\nfp=");
        pos = append_hex(out, sizeof(out), pos, fp);
        pos = append_text(out, sizeof(out), pos, "\nminecraft_start=");
        pos = append_hex(out, sizeof(out), pos, MINECRAFT_START);
        pos = append_text(out, sizeof(out), pos, "\nminecraft_end=");
        pos = append_hex(out, sizeof(out), pos, MINECRAFT_END);

        if (pc >= MINECRAFT_START && pc < MINECRAFT_END) {
            pos = append_text(out, sizeof(out), pos, "\npc_minecraft_rel=");
            pos = append_hex(out, sizeof(out), pos, pc - MINECRAFT_START);
        }
        if (lr >= MINECRAFT_START && lr < MINECRAFT_END) {
            pos = append_text(out, sizeof(out), pos, "\nlr_minecraft_rel=");
            pos = append_hex(out, sizeof(out), pos, lr - MINECRAFT_START);
        }

#if defined(__aarch64__)
        if (signal_number == SIGABRT && sp != 0 && MINECRAFT_END > MINECRAFT_START) {
            pos = append_text(out, sizeof(out), pos, "\nstack_minecraft_candidates=");
            const uintptr_t* words = (const uintptr_t*) sp;
            int found = 0;
            for (size_t index = 0; index < STACK_SCAN_WORDS && found < STACK_CANDIDATE_CAP; ++index) {
                uintptr_t value = words[index];
                if (value >= MINECRAFT_START && value < MINECRAFT_END) {
                    if (found > 0) pos = append_text(out, sizeof(out), pos, ",");
                    pos = append_hex(out, sizeof(out), pos, value - MINECRAFT_START);
                    pos = append_text(out, sizeof(out), pos, "@+");
                    pos = append_hex(out, sizeof(out), pos, index * sizeof(uintptr_t));
                    ++found;
                }
            }
            if (found == 0) pos = append_text(out, sizeof(out), pos, "NONE");
        }
#endif
        pos = append_text(out, sizeof(out), pos, "\n=== END SIGNAL ===\n");
        write_all(fd, out, pos);
        fsync(fd);
        close(fd);
    }

    struct sigaction reset_action;
    memset(&reset_action, 0, sizeof(reset_action));
    sigemptyset(&reset_action.sa_mask);
    reset_action.sa_handler = SIG_DFL;
    sigaction(signal_number, &reset_action, NULL);
    syscall(SYS_tgkill, getpid(), syscall(SYS_gettid), signal_number);
    _exit(128 + signal_number);
}

static int install_handlers(void) {
    const int signals[] = {SIGABRT, SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGTRAP};
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    sigemptyset(&action.sa_mask);
    action.sa_sigaction = signal_handler;
    action.sa_flags = SA_SIGINFO | SA_RESTART;

    int installed = 0;
    for (size_t i = 0; i < sizeof(signals) / sizeof(signals[0]); ++i) {
        if (sigaction(signals[i], &action, NULL) == 0) ++installed;
    }
    return installed;
}

JNIEXPORT jstring JNICALL
Java_com_balzikz_mathclient_LinkerBridge_nativeInstallSignalTrace(
        JNIEnv* env, jclass type, jstring path) {
    (void) type;
    if (path == NULL) return (*env)->NewStringUTF(env, "SIGNAL TRACE: NULL PATH");
    const char* requested = (*env)->GetStringUTFChars(env, path, NULL);
    if (requested == NULL) return (*env)->NewStringUTF(env, "SIGNAL TRACE: UTF ERROR");
    snprintf(TRACE_PATH, sizeof(TRACE_PATH), "%s", requested);
    (*env)->ReleaseStringUTFChars(env, path, requested);

    refresh_minecraft_range();
    int installed = install_handlers();
    append_normal_record("INSTALL");

    char result[256];
    snprintf(result, sizeof(result),
            "SIGNAL TRACE READY handlers=%d minecraft=0x%llx-0x%llx",
            installed,
            (unsigned long long) MINECRAFT_START,
            (unsigned long long) MINECRAFT_END);
    return (*env)->NewStringUTF(env, result);
}

JNIEXPORT jstring JNICALL
Java_com_balzikz_mathclient_LinkerBridge_nativeRefreshSignalTrace(
        JNIEnv* env, jclass type) {
    (void) type;
    refresh_minecraft_range();
    int installed = install_handlers();
    append_normal_record("REFRESH");

    char result[256];
    snprintf(result, sizeof(result),
            "SIGNAL TRACE REFRESHED handlers=%d minecraft=0x%llx-0x%llx",
            installed,
            (unsigned long long) MINECRAFT_START,
            (unsigned long long) MINECRAFT_END);
    return (*env)->NewStringUTF(env, result);
}

JNIEXPORT void JNICALL
Java_com_balzikz_mathclient_LinkerBridge_nativeSetSignalMarker(
        JNIEnv* env, jclass type, jstring marker) {
    (void) type;
    if (marker == NULL) {
        snprintf(CURRENT_MARKER, sizeof(CURRENT_MARKER), "%s", "NULL");
        return;
    }
    const char* value = (*env)->GetStringUTFChars(env, marker, NULL);
    if (value == NULL) return;
    snprintf(CURRENT_MARKER, sizeof(CURRENT_MARKER), "%s", value);
    (*env)->ReleaseStringUTFChars(env, marker, value);
}
