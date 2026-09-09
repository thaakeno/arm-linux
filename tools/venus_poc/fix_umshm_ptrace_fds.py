#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("linux-um-arm64")
mem = root / "arch/um/os-Linux/skas/mem.c"
proc = root / "arch/um/os-Linux/skas/process.c"
stub = root / "arch/um/kernel/skas/stub.c"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing expected source fragment: {label}")
    if text.count(old) != 1:
        raise SystemExit(f"expected exactly one source fragment for {label}, got {text.count(old)}")
    return text.replace(old, new, 1)

# In ptrace mode UML historically assumes every backing FD already exists in the
# traced stub with the same number. Venus umshm FDs arrive later via SCM_RIGHTS,
# so that assumption is false. Reuse the existing syscall_fd_map for ptrace too.
s = mem.read_text()
s = replace_once(
    s,
    "\t/* Find an FD slot (or flush and use first) */\n\tif (!using_seccomp)\n\t\treturn fd;\n\n\t/* Already crashed, value does not matter */",
    "\t/* Find an FD slot (or flush and use first).  ptrace mode also needs\n\t * an FD map: umshm backing FDs can arrive after the stub was exec'd. */\n\n\t/* Already crashed, value does not matter */",
    "get_stub_fd ptrace bypass",
)
s = replace_once(
    s,
    "\t\tint prev_fd = sc->mem.fd;\n\n\t\tif (using_seccomp)\n\t\t\tprev_fd = mm_idp->syscall_fd_map[sc->mem.fd];\n\n\t\tif (phys_fd == prev_fd) {",
    "\t\tint prev_fd = mm_idp->syscall_fd_map[sc->mem.fd];\n\n\t\tif (phys_fd == prev_fd) {",
    "mmap compression fd lookup",
)
mem.write_text(s)

s = proc.read_text()
helper_anchor = "static inline long do_syscall_stub(struct mm_id *mm_idp)\n{"
helper = r'''static int send_stub_fds_ptrace(struct mm_id *mm_idp)
{
	const char byte = 0;
	struct iovec iov = {
		.iov_base = (void *)&byte,
		.iov_len = sizeof(byte),
	};
	union {
		char data[CMSG_SPACE(sizeof(mm_idp->syscall_fd_map))];
		struct cmsghdr align;
	} ctrl = {};
	struct msghdr msgh = {
		.msg_iov = &iov,
		.msg_iovlen = 1,
	};
	unsigned int fds_size;
	struct cmsghdr *cmsg;
	int ret;

	if (!mm_idp->syscall_fd_num)
		return 0;

	fds_size = sizeof(int) * mm_idp->syscall_fd_num;
	msgh.msg_control = ctrl.data;
	msgh.msg_controllen = CMSG_SPACE(fds_size);
	cmsg = CMSG_FIRSTHDR(&msgh);
	cmsg->cmsg_level = SOL_SOCKET;
	cmsg->cmsg_type = SCM_RIGHTS;
	cmsg->cmsg_len = CMSG_LEN(fds_size);
	memcpy(CMSG_DATA(cmsg), mm_idp->syscall_fd_map, fds_size);

	do {
		ret = syscall(__NR_sendmsg, mm_idp->sock, &msgh, 0);
	} while (ret < 0 && errno == EINTR);

	return ret < 0 ? -errno : 0;
}

static inline long do_syscall_stub(struct mm_id *mm_idp)
{'''
s = replace_once(s, helper_anchor, helper, "ptrace fd sender helper anchor")

s = replace_once(
    s,
    "\t} else {\n\t\tn = put_host_regs(pid, syscall_regs);",
    "\t} else {\n\t\t/* Tell the ptrace stub how many descriptors to receive, then pass\n\t\t * them over the socket it keeps as fd 0. */\n\t\tproc_data->restart_wait = mm_idp->syscall_fd_num;\n\t\terr = send_stub_fds_ptrace(mm_idp);\n\t\tif (err < 0) {\n\t\t\tprintk(UM_KERN_ERR \"%s : sendmsg FDs failed, errno = %d\\n\",\n\t\t\t       __func__, -err);\n\t\t\tmm_idp->syscall_data_len = err;\n\t\t\treturn err;\n\t\t}\n\n\t\tn = put_host_regs(pid, syscall_regs);",
    "ptrace send fds before PTRACE_CONT",
)

s = replace_once(
    s,
    "\tif (using_seccomp)\n\t\tmm_idp->syscall_fd_num = 0;\n\n\treturn mm_idp->syscall_data_len;",
    "\tmm_idp->syscall_fd_num = 0;\n\n\treturn mm_idp->syscall_data_len;",
    "reset fd map after ptrace flush",
)

s = replace_once(
    s,
    "\tclose(tramp_data.sockpair[0]);\n\tif (using_seccomp)\n\t\tmm_id->sock = tramp_data.sockpair[1];\n\telse\n\t\tclose(tramp_data.sockpair[1]);",
    "\tclose(tramp_data.sockpair[0]);\n\t/* Keep the kernel-side socket in both modes.  SECCOMP already uses it\n\t * for SCM_RIGHTS; ptrace now uses the same transport for late umshm FDs. */\n\tmm_id->sock = tramp_data.sockpair[1];",
    "retain ptrace fd socket",
)
proc.write_text(s)

s = stub.read_text()
old_handler = r'''void __section(".__syscall_stub")
stub_syscall_handler(void)
{
	syscall_handler(NULL);

	trap_myself();
}'''
new_handler = r'''void __section(".__syscall_stub")
stub_syscall_handler(void)
{
	struct stub_data *d = get_stub_data();
	char rcv_data;
	union {
		char data[CMSG_SPACE(sizeof(int) * STUB_MAX_FDS)];
		struct cmsghdr align;
	} ctrl = {};
	struct iovec iov = {
		.iov_base = &rcv_data,
		.iov_len = 1,
	};
	struct msghdr msghdr = {
		.msg_iov = &iov,
		.msg_iovlen = 1,
		.msg_control = &ctrl,
		.msg_controllen = sizeof(ctrl),
	};
	struct cmsghdr *fd_msg;
	int *fd_map = NULL;
	int num_fds = 0;
	long res;

	/* ptrace mode keeps the init socket as fd 0.  The kernel writes the
	 * number of queued SCM_RIGHTS descriptors into restart_wait before it
	 * resumes us.  Avoid recvmsg for pure munmap batches. */
	if (d->restart_wait) {
		do {
			res = stub_syscall3(__NR_recvmsg, 0,
					    (unsigned long)&msghdr, 0);
		} while (res == -EINTR);

		if (res != iov.iov_len) {
			d->err = res < 0 ? res : -EIO;
			d->syscall_data_len = 0;
			trap_myself();
			return;
		}

		fd_msg = msghdr.msg_control;
		if (msghdr.msg_controllen >= CMSG_LEN(sizeof(int)) &&
		    fd_msg->cmsg_level == SOL_SOCKET &&
		    fd_msg->cmsg_type == SCM_RIGHTS) {
			fd_map = (void *)CMSG_DATA(fd_msg);
			num_fds = (fd_msg->cmsg_len - CMSG_LEN(0)) / sizeof(int);
		}

		if (!fd_map || num_fds < d->restart_wait) {
			d->err = -EBADMSG;
			d->syscall_data_len = 0;
			trap_myself();
			return;
		}
	}

	d->restart_wait = 0;
	res = syscall_handler(fd_map);

	while (num_fds)
		stub_syscall1(__NR_close, fd_map[--num_fds]);

	(void)res;
	trap_myself();
}'''
s = replace_once(s, old_handler, new_handler, "ptrace stub syscall handler")
stub.write_text(s)

print("umshm ptrace SCM_RIGHTS fd passing enabled")
