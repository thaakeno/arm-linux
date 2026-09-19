#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <signal.h>
#include <stdint.h>
#include <string.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>

/*
 * XWayland runs inside the Android app process seccomp boundary.  Keep Xorg's
 * existing SIGSYS behavior, but wrap the installed handler so a seccomp TRAP
 * records the exact syscall/arch/code before Xorg aborts or handles it.
 *
 * For Linux syscalls that are explicitly designed to have an ENOSYS fallback
 * (newer optional kernel APIs such as rseq/clone3/close_range/Landlock), the
 * wrapper converts Android's SECCOMP_RET_TRAP into -ENOSYS on AArch64. That is
 * the same compatibility signal applications receive on an older Linux kernel;
 * it does not weaken or bypass Android's seccomp policy. Unknown SIGSYS events
 * still go to Xorg's original handler after being logged.
 */

typedef int (*sigaction_fn)(int, const struct sigaction *, struct sigaction *);

static sigaction_fn real_sigaction_fn;
static struct sigaction vessel_sigsys_user_action;
static int vessel_sigsys_user_action_valid;

static sigaction_fn real_sigaction(void) {
    if (!real_sigaction_fn) {
        real_sigaction_fn = (sigaction_fn)dlsym(RTLD_NEXT, "sigaction");
    }
    return real_sigaction_fn;
}

static size_t append_literal(char *dst, size_t pos, size_t cap, const char *src) {
    while (*src && pos < cap) dst[pos++] = *src++;
    return pos;
}

static size_t append_uint(char *dst, size_t pos, size_t cap, unsigned long value) {
    char tmp[32];
    size_t n = 0;
    do {
        tmp[n++] = (char)('0' + (value % 10));
        value /= 10;
    } while (value && n < sizeof(tmp));
    while (n && pos < cap) dst[pos++] = tmp[--n];
    return pos;
}

static void log_sigsys(const siginfo_t *info) {
    char buf[192];
    size_t n = 0;
    n = append_literal(buf, n, sizeof(buf), "VESSEL_XWAYLAND_SIGSYS syscall=");
#ifdef __linux__
    n = append_uint(buf, n, sizeof(buf), (unsigned long)info->si_syscall);
    n = append_literal(buf, n, sizeof(buf), " arch=");
    n = append_uint(buf, n, sizeof(buf), (unsigned long)info->si_arch);
#else
    n = append_literal(buf, n, sizeof(buf), "unknown arch=unknown");
#endif
    n = append_literal(buf, n, sizeof(buf), " code=");
    n = append_uint(buf, n, sizeof(buf), (unsigned long)info->si_code);
    if (n < sizeof(buf)) buf[n++] = '\n';
    (void)write(STDERR_FILENO, buf, n);
}

static int syscall_has_safe_enosys_fallback(int nr) {
#ifdef SYS_rseq
    if (nr == SYS_rseq) return 1;
#endif
#ifdef SYS_clone3
    if (nr == SYS_clone3) return 1;
#endif
#ifdef SYS_close_range
    if (nr == SYS_close_range) return 1;
#endif
#ifdef SYS_openat2
    if (nr == SYS_openat2) return 1;
#endif
#ifdef SYS_faccessat2
    if (nr == SYS_faccessat2) return 1;
#endif
#ifdef SYS_epoll_pwait2
    if (nr == SYS_epoll_pwait2) return 1;
#endif
#ifdef SYS_landlock_create_ruleset
    if (nr == SYS_landlock_create_ruleset) return 1;
#endif
#ifdef SYS_landlock_add_rule
    if (nr == SYS_landlock_add_rule) return 1;
#endif
#ifdef SYS_landlock_restrict_self
    if (nr == SYS_landlock_restrict_self) return 1;
#endif
#ifdef SYS_io_uring_setup
    if (nr == SYS_io_uring_setup) return 1;
#endif
#ifdef SYS_io_uring_enter
    if (nr == SYS_io_uring_enter) return 1;
#endif
#ifdef SYS_io_uring_register
    if (nr == SYS_io_uring_register) return 1;
#endif
    return 0;
}

static int return_enosys_for_optional_seccomp(siginfo_t *info, void *context) {
#if defined(__linux__) && defined(__aarch64__)
    if (!info || !context || info->si_code != SYS_SECCOMP) return 0;
    if (!syscall_has_safe_enosys_fallback(info->si_syscall)) return 0;

    ucontext_t *uc = (ucontext_t *)context;
    uc->uc_mcontext.regs[0] = (uint64_t)(-(int64_t)ENOSYS);

    static const char recovered[] = "VESSEL_XWAYLAND_SIGSYS action=return-ENOSYS\n";
    (void)write(STDERR_FILENO, recovered, sizeof(recovered) - 1);
    return 1;
#else
    (void)info;
    (void)context;
    return 0;
#endif
}

static void vessel_sigsys_trampoline(int signo, siginfo_t *info, void *context) {
    if (info) log_sigsys(info);
    if (return_enosys_for_optional_seccomp(info, context)) return;

    if (!vessel_sigsys_user_action_valid) return;

    struct sigaction action = vessel_sigsys_user_action;
    if (action.sa_flags & SA_SIGINFO) {
        if (action.sa_sigaction) action.sa_sigaction(signo, info, context);
        return;
    }

    if (action.sa_handler == SIG_IGN) return;
    if (action.sa_handler == SIG_DFL || action.sa_handler == NULL) {
        sigaction_fn fn = real_sigaction();
        if (fn) {
            struct sigaction dfl;
            memset(&dfl, 0, sizeof(dfl));
            sigemptyset(&dfl.sa_mask);
            dfl.sa_handler = SIG_DFL;
            (void)fn(SIGSYS, &dfl, NULL);
        }
        (void)raise(SIGSYS);
        _exit(128 + SIGSYS);
    }

    action.sa_handler(signo);
}

__attribute__((constructor))
static void vessel_sigsys_install_early(void) {
    sigaction_fn fn = real_sigaction();
    if (!fn) return;

    struct sigaction current;
    if (fn(SIGSYS, NULL, &current) != 0) return;

    vessel_sigsys_user_action = current;
    vessel_sigsys_user_action_valid = 1;

    struct sigaction wrapped = current;
    wrapped.sa_flags |= SA_SIGINFO;
    wrapped.sa_sigaction = vessel_sigsys_trampoline;
    (void)fn(SIGSYS, &wrapped, NULL);
}

int sigaction(int signum, const struct sigaction *act, struct sigaction *oldact) {
    sigaction_fn fn = real_sigaction();
    if (!fn) return -1;

    if (signum != SIGSYS) {
        return fn(signum, act, oldact);
    }

    if (oldact) {
        if (vessel_sigsys_user_action_valid) {
            *oldact = vessel_sigsys_user_action;
        } else {
            if (fn(signum, NULL, oldact) != 0) return -1;
        }
    }

    if (!act) return 0;

    vessel_sigsys_user_action = *act;
    vessel_sigsys_user_action_valid = 1;

    struct sigaction wrapped = *act;
    wrapped.sa_flags |= SA_SIGINFO;
    wrapped.sa_sigaction = vessel_sigsys_trampoline;
    return fn(signum, &wrapped, NULL);
}
