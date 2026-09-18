#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <jni.h>

#include <array>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

namespace {

constexpr size_t STAT_CAP = 2048;

long long monotonic_ms() {
    timespec now{};
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return -1;
    return static_cast<long long>(now.tv_sec) * 1000LL + now.tv_nsec / 1000000LL;
}

bool wait_readable(int fd, int timeout_ms) {
    const long long start = monotonic_ms();
    if (start < 0) return false;
    const long long deadline = start + timeout_ms;
    for (;;) {
        const long long now = monotonic_ms();
        if (now < 0 || now >= deadline) return false;
        pollfd item{fd, POLLIN, 0};
        const int rc = poll(&item, 1, static_cast<int>(deadline - now));
        if (rc < 0 && errno == EINTR) continue;
        return rc > 0 && (item.revents & POLLIN);
    }
}

bool validate_child_stat(const char* stat, pid_t child, pid_t parent) {
    if (!stat || child <= 1 || parent <= 1) return false;
    char* end = nullptr;
    const long parsed_pid = strtol(stat, &end, 10);
    if (end == stat || parsed_pid != child) return false;
    const char* close = strrchr(stat, ')');
    if (!close) return false;
    char state = 0;
    int ppid = 0;
    int pgrp = 0;
    int session = 0;
    if (sscanf(close + 1, " %c %d %d %d", &state, &ppid, &pgrp, &session) != 4) return false;
    (void)state;
    return ppid == parent && pgrp == child && session == child;
}

bool child_identity(int fd) {
    std::array<char, STAT_CAP> stat{};
    const int proc = open("/proc/self/stat", O_RDONLY | O_CLOEXEC);
    if (proc < 0) return false;
    ssize_t size;
    do {
        size = read(proc, stat.data(), stat.size() - 1);
    } while (size < 0 && errno == EINTR);
    close(proc);
    if (size <= 0) return false;
    stat[static_cast<size_t>(size)] = '\0';

    ssize_t sent;
    do {
        sent = send(fd, stat.data(), static_cast<size_t>(size) + 1, MSG_NOSIGNAL);
    } while (sent < 0 && errno == EINTR);
    if (sent != size + 1 || !wait_readable(fd, 3000)) return false;

    uint8_t ack = 0;
    ssize_t got;
    do {
        got = recv(fd, &ack, 1, 0);
    } while (got < 0 && errno == EINTR);
    return got == 1 && ack == 1;
}

std::string parent_identity(int fd, pid_t child) {
    std::array<char, STAT_CAP> stat{};
    ssize_t size = -1;
    if (wait_readable(fd, 3000)) {
        do {
            size = recv(fd, stat.data(), stat.size() - 1, 0);
        } while (size < 0 && errno == EINTR);
    }
    bool valid = false;
    if (size > 0) {
        stat[static_cast<size_t>(size)] = '\0';
        valid = validate_child_stat(stat.data(), child, getpid());
    }
    const uint8_t ack = valid ? 1 : 0;
    (void)send(fd, &ack, 1, MSG_NOSIGNAL);
    return valid ? std::string(stat.data()) : std::string();
}

void kill_reap(pid_t pid) {
    if (pid <= 1) return;
    kill(pid, SIGKILL);
    while (waitpid(pid, nullptr, 0) < 0 && errno == EINTR) {}
}

std::vector<std::string> strings(JNIEnv* env, jobjectArray input) {
    std::vector<std::string> result;
    if (!input) return result;
    const jsize count = env->GetArrayLength(input);
    result.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(input, i));
        if (!item) continue;
        const char* chars = env->GetStringUTFChars(item, nullptr);
        if (!chars) {
            env->DeleteLocalRef(item);
            return {};
        }
        result.emplace_back(chars);
        env->ReleaseStringUTFChars(item, chars);
        env->DeleteLocalRef(item);
    }
    return result;
}

std::vector<char*> pointers(std::vector<std::string>& values) {
    std::vector<char*> result;
    result.reserve(values.size() + 1);
    for (auto& value : values) result.push_back(value.data());
    result.push_back(nullptr);
    return result;
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_dreamlinux_VesselProrootProcessNative_nativeSpawn(
    JNIEnv* env,
    jobject,
    jobjectArray argv_array,
    jobjectArray env_array,
    jstring cwd_string,
    jintArray pid_fd_out) {

    auto argv_values = strings(env, argv_array);
    auto env_values = strings(env, env_array);
    if (argv_values.empty() || !cwd_string || !pid_fd_out || env->GetArrayLength(pid_fd_out) < 2) {
        return nullptr;
    }
    auto argv = pointers(argv_values);
    auto envp = pointers(env_values);

    const char* cwd_raw = env->GetStringUTFChars(cwd_string, nullptr);
    if (!cwd_raw) return nullptr;
    const std::string cwd(cwd_raw);
    env->ReleaseStringUTFChars(cwd_string, cwd_raw);

    int output[2] = {-1, -1};
    int identity[2] = {-1, -1};
    if (pipe2(output, O_CLOEXEC) != 0 ||
        socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, identity) != 0) {
        if (output[0] >= 0) close(output[0]);
        if (output[1] >= 0) close(output[1]);
        if (identity[0] >= 0) close(identity[0]);
        if (identity[1] >= 0) close(identity[1]);
        return nullptr;
    }

    const pid_t pid = fork();
    if (pid < 0) {
        close(output[0]); close(output[1]);
        close(identity[0]); close(identity[1]);
        return nullptr;
    }

    if (pid == 0) {
        close(output[0]);
        close(identity[0]);
        sigset_t signals;
        sigfillset(&signals);
        sigprocmask(SIG_UNBLOCK, &signals, nullptr);

        if (setsid() < 0 || !child_identity(identity[1])) _exit(125);
        close(identity[1]);

        if (dup2(output[1], STDOUT_FILENO) < 0 ||
            dup2(output[1], STDERR_FILENO) < 0) {
            _exit(126);
        }
        close(output[1]);

        if (chdir(cwd.c_str()) != 0) _exit(126);
        execve(argv[0], argv.data(), envp.data());
        _exit(127);
    }

    close(output[1]);
    close(identity[1]);
    std::string birth = parent_identity(identity[0], pid);
    close(identity[0]);
    if (birth.empty()) {
        close(output[0]);
        kill_reap(pid);
        return nullptr;
    }

    jint pair[2] = {static_cast<jint>(pid), static_cast<jint>(output[0])};
    env->SetIntArrayRegion(pid_fd_out, 0, 2, pair);
    return env->NewStringUTF(birth.c_str());
}


extern "C" JNIEXPORT jint JNICALL
Java_com_example_dreamlinux_VesselProrootProcessNative_nativeWait(
    JNIEnv*,
    jobject,
    jint pid) {
    int status = 0;
    pid_t rc;
    do {
        rc = waitpid(static_cast<pid_t>(pid), &status, 0);
    } while (rc < 0 && errno == EINTR);
    if (rc < 0) return -errno;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return 0;
}
