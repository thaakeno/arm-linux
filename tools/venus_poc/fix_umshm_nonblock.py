#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "linux-um-arm64")
path = root / "arch/um/drivers/umshm_kern.c"
text = path.read_text()

old = '''            sock = os_connect_socket(uml_shm_socket);\n            if (sock < 0) {\n                msleep(100);\n                continue;\n            }\n            pr_info("umshm: connected to host control socket %s\\n", uml_shm_socket);\n'''
new = '''            sock = os_connect_socket(uml_shm_socket);\n            if (sock < 0) {\n                msleep(100);\n                continue;\n            }\n            if (os_set_fd_block(sock, 0) < 0) {\n                os_close_file(sock);\n                sock = -1;\n                msleep(100);\n                continue;\n            }\n            pr_info("umshm: connected to host control socket %s\\n", uml_shm_socket);\n'''
if old not in text:
    raise SystemExit("connect block not found")
text = text.replace(old, new, 1)

old2 = '''            n = os_rcv_fd_msg(sock, &fd, 1, &msg, sizeof(msg));\n            if (n <= 0) {\n                os_close_file(sock);\n                sock = -1;\n                break;\n            }\n'''
new2 = '''            n = os_rcv_fd_msg(sock, &fd, 1, &msg, sizeof(msg));\n            if (n == -EAGAIN) {\n                msleep(1);\n                continue;\n            }\n            if (n <= 0) {\n                os_close_file(sock);\n                sock = -1;\n                break;\n            }\n'''
if old2 not in text:
    raise SystemExit("recv block not found")
text = text.replace(old2, new2, 1)

path.write_text(text)
print("umshm control socket switched to nonblocking mode")
