/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Vessel PTY JNI.
 *
 * PTY/TerminalSession ABI derived from Termux terminal-emulator v0.118.0
 * (Terminal Emulator for Android code, Apache License 2.0 exception).
 * Vessel maintains this file directly; no source transforms or generated
 * monkey patches are applied at build time.
 *
 * Vessel addition: a pre-exec session-identity handshake. The child performs
 * setsid(), reads its own /proc/self/stat, and the parent validates + stores
 * that birth identity before allowing exec. Java claims the stat record once
 * immediately after TerminalSession.initializeEmulator().
 */
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#define UNUSED(x) x __attribute__((__unused__))
#define IDENTITY_SLOTS 16
#define STAT_CAP 2048

struct pending_identity {
    pid_t pid;
    char stat[STAT_CAP];
};

static pthread_mutex_t g_identity_lock = PTHREAD_MUTEX_INITIALIZER;
static struct pending_identity g_identities[IDENTITY_SLOTS];

__attribute__((visibility("default")))
const char* vessel_termux_pty_build_marker(void) {
    return "VESSEL_TERMUX_PTY_NDK29_DIRECT_SOURCE_V1";
}

static int throw_runtime_exception(JNIEnv* env, const char* message) {
    jclass type = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (type != NULL) (*env)->ThrowNew(env, type, message);
    return -1;
}

static long long monotonic_ms(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return -1;
    return now.tv_sec * 1000LL + now.tv_nsec / 1000000LL;
}

static int wait_readable(int fd, int timeout_ms) {
    const long long start = monotonic_ms();
    if (start < 0) return 0;
    const long long deadline = start + timeout_ms;
    for (;;) {
        const long long now = monotonic_ms();
        if (now < 0 || now >= deadline) return 0;
        struct pollfd item = { .fd = fd, .events = POLLIN, .revents = 0 };
        int rc = poll(&item, 1, (int)(deadline - now));
        if (rc < 0 && errno == EINTR) continue;
        return rc > 0 && (item.revents & POLLIN) != 0;
    }
}

static int validate_child_stat(const char* stat, pid_t child, pid_t parent) {
    if (stat == NULL || child <= 1 || parent <= 1) return 0;
    char* end = NULL;
    long parsed_pid = strtol(stat, &end, 10);
    if (end == stat || parsed_pid != child) return 0;

    const char* close = strrchr(stat, ')');
    if (close == NULL) return 0;
    char state = 0;
    int ppid = 0, pgrp = 0, session = 0;
    if (sscanf(close + 1, " %c %d %d %d", &state, &ppid, &pgrp, &session) != 4) return 0;
    (void)state;
    return ppid == parent && pgrp == child && session == child;
}

static int store_identity(pid_t pid, const char* stat) {
    int ok = 0;
    pthread_mutex_lock(&g_identity_lock);
    int free_slot = -1;
    for (int i = 0; i < IDENTITY_SLOTS; ++i) {
        if (g_identities[i].pid == pid) {
            free_slot = i;
            break;
        }
        if (free_slot < 0 && g_identities[i].pid == 0) free_slot = i;
    }
    if (free_slot >= 0) {
        g_identities[free_slot].pid = pid;
        snprintf(g_identities[free_slot].stat, sizeof(g_identities[free_slot].stat), "%s", stat);
        ok = 1;
    }
    pthread_mutex_unlock(&g_identity_lock);
    return ok;
}

static void drop_identity(pid_t pid) {
    pthread_mutex_lock(&g_identity_lock);
    for (int i = 0; i < IDENTITY_SLOTS; ++i) {
        if (g_identities[i].pid == pid) {
            g_identities[i].pid = 0;
            g_identities[i].stat[0] = 0;
            break;
        }
    }
    pthread_mutex_unlock(&g_identity_lock);
}

static jstring take_identity(JNIEnv* env, pid_t pid) {
    jstring result = NULL;
    pthread_mutex_lock(&g_identity_lock);
    for (int i = 0; i < IDENTITY_SLOTS; ++i) {
        if (g_identities[i].pid == pid) {
            result = (*env)->NewStringUTF(env, g_identities[i].stat);
            g_identities[i].pid = 0;
            g_identities[i].stat[0] = 0;
            break;
        }
    }
    pthread_mutex_unlock(&g_identity_lock);
    return result;
}

static int child_identity_handshake(int pair[2]) {
    close(pair[0]);
    char stat[STAT_CAP];
    int fd = open("/proc/self/stat", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;

    ssize_t size;
    do {
        size = read(fd, stat, sizeof(stat) - 1);
    } while (size < 0 && errno == EINTR);
    close(fd);
    if (size <= 0) return 0;
    stat[size] = 0;

    ssize_t sent;
    do {
        sent = send(pair[1], stat, (size_t)size + 1, MSG_NOSIGNAL);
    } while (sent < 0 && errno == EINTR);
    if (sent != size + 1) return 0;

    char ack = 0;
    if (!wait_readable(pair[1], 3000)) return 0;
    ssize_t got;
    do {
        got = recv(pair[1], &ack, 1, 0);
    } while (got < 0 && errno == EINTR);
    close(pair[1]);
    return got == 1 && ack == 1;
}

static int parent_identity_handshake(int pair[2], pid_t child) {
    close(pair[1]);
    char stat[STAT_CAP];
    ssize_t size = -1;
    if (wait_readable(pair[0], 3000)) {
        do {
            size = recv(pair[0], stat, sizeof(stat) - 1, 0);
        } while (size < 0 && errno == EINTR);
    }
    int valid = 0;
    if (size > 0) {
        stat[size] = 0;
        valid = validate_child_stat(stat, child, getpid()) && store_identity(child, stat);
    }
    char ack = valid ? 1 : 0;
    if (send(pair[0], &ack, 1, MSG_NOSIGNAL) != 1) valid = 0;
    close(pair[0]);
    return valid;
}

static void kill_and_reap(pid_t child) {
    if (child <= 1) return;
    kill(child, SIGKILL);
    while (waitpid(child, NULL, 0) < 0 && errno == EINTR) {}
    drop_identity(child);
}

static int create_subprocess(
    JNIEnv* env,
    const char* cmd,
    const char* cwd,
    char* const argv[],
    char** envp,
    int* process_id,
    jint rows,
    jint columns
) {
    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) return throw_runtime_exception(env, "Cannot open /dev/ptmx");

    char devname[64];
    if (grantpt(ptm) != 0 || unlockpt(ptm) != 0 || ptsname_r(ptm, devname, sizeof(devname)) != 0) {
        close(ptm);
        return throw_runtime_exception(env, "Cannot prepare PTY slave");
    }

    struct termios tios;
    if (tcgetattr(ptm, &tios) == 0) {
        tios.c_iflag |= IUTF8;
        tios.c_iflag &= ~(IXON | IXOFF);
        (void)tcsetattr(ptm, TCSANOW, &tios);
    }

    struct winsize size = { .ws_row = (unsigned short)rows, .ws_col = (unsigned short)columns };
    (void)ioctl(ptm, TIOCSWINSZ, &size);

    int identity_pair[2] = {-1, -1};
    if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, identity_pair) != 0) {
        close(ptm);
        return throw_runtime_exception(env, "Cannot prepare PTY identity channel");
    }

    pid_t pid = fork();
    if (pid < 0) {
        close(identity_pair[0]);
        close(identity_pair[1]);
        close(ptm);
        return throw_runtime_exception(env, "Fork failed");
    }

    if (pid > 0) {
        if (!parent_identity_handshake(identity_pair, pid)) {
            close(ptm);
            kill_and_reap(pid);
            return throw_runtime_exception(env, "Cannot verify PTY session identity before exec");
        }
        *process_id = (int)pid;
        return ptm;
    }

    sigset_t signals;
    sigfillset(&signals);
    sigprocmask(SIG_UNBLOCK, &signals, NULL);

    close(ptm);
    if (setsid() < 0) _exit(125);
    if (!child_identity_handshake(identity_pair)) _exit(125);

    int pts = open(devname, O_RDWR);
    if (pts < 0) _exit(126);
    if (dup2(pts, 0) < 0 || dup2(pts, 1) < 0 || dup2(pts, 2) < 0) _exit(126);
    if (pts > 2) close(pts);

    DIR* self_dir = opendir("/proc/self/fd");
    if (self_dir != NULL) {
        const int self_dir_fd = dirfd(self_dir);
        struct dirent* entry;
        while ((entry = readdir(self_dir)) != NULL) {
            int fd = atoi(entry->d_name);
            if (fd > 2 && fd != self_dir_fd) close(fd);
        }
        closedir(self_dir);
    }

    clearenv();
    if (envp != NULL) {
        for (char** current = envp; *current != NULL; ++current) putenv(*current);
    }

    if (chdir(cwd) != 0) {
        dprintf(2, "chdir(%s): %s\n", cwd, strerror(errno));
    }

    execvp(cmd, argv);
    dprintf(2, "exec(%s): %s\n", cmd, strerror(errno));
    _exit(127);
}

JNIEXPORT jint JNICALL
Java_com_termux_terminal_JNI_createSubprocess(
    JNIEnv* env,
    jclass UNUSED(clazz),
    jstring cmd,
    jstring cwd,
    jobjectArray args,
    jobjectArray env_vars,
    jintArray process_id_array,
    jint rows,
    jint columns
) {
    const jsize argc = args ? (*env)->GetArrayLength(env, args) : 0;
    char** argv = calloc((size_t)argc + 1, sizeof(char*));
    if (argv == NULL) return throw_runtime_exception(env, "Cannot allocate argv");

    for (jsize i = 0; i < argc; ++i) {
        jstring item = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        const char* value = (*env)->GetStringUTFChars(env, item, NULL);
        if (value == NULL) return -1;
        argv[i] = strdup(value);
        (*env)->ReleaseStringUTFChars(env, item, value);
        (*env)->DeleteLocalRef(env, item);
        if (argv[i] == NULL) return throw_runtime_exception(env, "Cannot copy argv");
    }

    const jsize envc = env_vars ? (*env)->GetArrayLength(env, env_vars) : 0;
    char** envp = calloc((size_t)envc + 1, sizeof(char*));
    if (envp == NULL) return throw_runtime_exception(env, "Cannot allocate environment");
    for (jsize i = 0; i < envc; ++i) {
        jstring item = (jstring)(*env)->GetObjectArrayElement(env, env_vars, i);
        const char* value = (*env)->GetStringUTFChars(env, item, NULL);
        if (value == NULL) return -1;
        envp[i] = strdup(value);
        (*env)->ReleaseStringUTFChars(env, item, value);
        (*env)->DeleteLocalRef(env, item);
        if (envp[i] == NULL) return throw_runtime_exception(env, "Cannot copy environment");
    }

    const char* cmd_utf8 = (*env)->GetStringUTFChars(env, cmd, NULL);
    const char* cwd_utf8 = (*env)->GetStringUTFChars(env, cwd, NULL);
    if (cmd_utf8 == NULL || cwd_utf8 == NULL) return -1;

    int process_id = 0;
    int ptm = create_subprocess(env, cmd_utf8, cwd_utf8, argv, envp, &process_id, rows, columns);

    (*env)->ReleaseStringUTFChars(env, cmd, cmd_utf8);
    (*env)->ReleaseStringUTFChars(env, cwd, cwd_utf8);
    for (jsize i = 0; i < argc; ++i) free(argv[i]);
    for (jsize i = 0; i < envc; ++i) free(envp[i]);
    free(argv);
    free(envp);

    if (ptm < 0) return ptm;
    jint* out = (*env)->GetIntArrayElements(env, process_id_array, NULL);
    if (out == NULL) {
        close(ptm);
        kill_and_reap(process_id);
        return throw_runtime_exception(env, "Cannot write PTY process id");
    }
    out[0] = process_id;
    (*env)->ReleaseIntArrayElements(env, process_id_array, out, 0);
    return ptm;
}

JNIEXPORT void JNICALL
Java_com_termux_terminal_JNI_setPtyWindowSize(
    JNIEnv* UNUSED(env),
    jclass UNUSED(clazz),
    jint fd,
    jint rows,
    jint columns
) {
    struct winsize size = { .ws_row = (unsigned short)rows, .ws_col = (unsigned short)columns };
    (void)ioctl(fd, TIOCSWINSZ, &size);
}

JNIEXPORT void JNICALL
Java_com_termux_terminal_JNI_setPtyUTF8Mode(
    JNIEnv* UNUSED(env),
    jclass UNUSED(clazz),
    jint fd
) {
    struct termios tios;
    if (tcgetattr(fd, &tios) == 0 && (tios.c_iflag & IUTF8) == 0) {
        tios.c_iflag |= IUTF8;
        (void)tcsetattr(fd, TCSANOW, &tios);
    }
}

JNIEXPORT jint JNICALL
Java_com_termux_terminal_JNI_waitFor(
    JNIEnv* UNUSED(env),
    jclass UNUSED(clazz),
    jint pid
) {
    int status = 0;
    pid_t rc;
    do {
        rc = waitpid(pid, &status, 0);
    } while (rc < 0 && errno == EINTR);
    drop_identity(pid);
    if (rc < 0) return -errno;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_termux_terminal_JNI_close(
    JNIEnv* UNUSED(env),
    jclass UNUSED(clazz),
    jint fd
) {
    if (fd >= 0) close(fd);
}

JNIEXPORT jstring JNICALL
Java_com_example_dreamlinux_VesselPtyNative_nativeTakeIdentity(
    JNIEnv* env,
    jclass UNUSED(clazz),
    jint pid
) {
    return take_identity(env, pid);
}
