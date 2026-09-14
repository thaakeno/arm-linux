#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(f"usage: {sys.argv[0]} <linux-um-arm64 tree>")

    root = Path(sys.argv[1])
    header = root / "arch/um/drivers/vhost_user.h"
    source = root / "arch/um/drivers/virtio_uml.c"
    if not header.is_file() or not source.is_file():
        raise SystemExit("expected UML vhost-user sources were not found")

    h = header.read_text()
    if "VHOST_USER_GPU_SET_SOCKET = 33" not in h:
        h = replace_once(
            h,
            "\tVHOST_USER_SET_CONFIG = 25,\n\tVHOST_USER_VRING_KICK = 35,",
            "\tVHOST_USER_SET_CONFIG = 25,\n\tVHOST_USER_GPU_SET_SOCKET = 33,\n\tVHOST_USER_VRING_KICK = 35,",
            "vhost-user GPU request enum",
        )
        header.write_text(h)

    s = source.read_text()
    if "#include <linux/virtio_ids.h>" not in s:
        s = replace_once(
            s,
            "#include <linux/virtio.h>\n",
            "#include <linux/virtio.h>\n#include <linux/virtio_ids.h>\n",
            "virtio_ids include",
        )

    helper_marker = "static int vessel_vhost_user_set_gpu_socket(struct virtio_uml_device *vu_dev)"
    if helper_marker not in s:
        anchor = """static int vhost_user_send_no_payload_fd(struct virtio_uml_device *vu_dev,
\t\t\t\t\t u32 request, int fd)
{
\tstruct vhost_user_msg msg = {
\t\t.header.request = request,
\t};

\treturn vhost_user_send(vu_dev, false, &msg, &fd, 1);
}
"""
        helper = anchor + r'''
/*
 * vhost-user-gpu is special: besides the normal vhost-user control socket it
 * needs a second display-protocol socket (VHOST_USER_GPU_SET_SOCKET).  QEMU
 * normally owns that socket.  In Vessel the UML kernel is the virtio frontend,
 * so hand the backend a connection to a host-side display relay instead.
 *
 * The sidecar path is deterministic: <main vhost-user socket>.display.  This
 * keeps virtio_uml.device= backwards compatible and lets the Termux runtime
 * start the relay before UML boots.
 */
static int vessel_vhost_user_set_gpu_socket(struct virtio_uml_device *vu_dev)
{
	char *display_path;
	int fd, rc;

	if (vu_dev->pdata->virtio_device_id != VIRTIO_ID_GPU)
		return 0;

	display_path = kasprintf(GFP_KERNEL, "%s.display",
				 vu_dev->pdata->socket_path);
	if (!display_path)
		return -ENOMEM;

	do {
		fd = os_connect_socket(display_path);
	} while (fd == -EINTR);
	if (fd < 0) {
		vu_err(vu_dev, "Vessel GPU display relay connect %s failed: %d\n",
		       display_path, fd);
		kfree(display_path);
		return fd;
	}

	rc = vhost_user_send_no_payload_fd(vu_dev,
					    VHOST_USER_GPU_SET_SOCKET, fd);
	os_close_file(fd);
	if (rc)
		vu_err(vu_dev, "Vessel GPU_SET_SOCKET failed: %d\n", rc);
	else
		dev_info(&vu_dev->vdev.dev,
			 "Vessel vhost-user-gpu display relay attached at %s\n",
			 display_path);
	kfree(display_path);
	return rc;
}
'''
        s = replace_once(s, anchor, helper, "GPU socket helper insertion")

    if "vessel_vhost_user_set_gpu_socket(vu_dev);" not in s:
        old = """\trc = vhost_user_init(vu_dev);
\tif (rc)
\t\tgoto error_init;

\tplatform_set_drvdata(pdev, vu_dev);
"""
        new = """\trc = vhost_user_init(vu_dev);
\tif (rc)
\t\tgoto error_init;

\trc = vessel_vhost_user_set_gpu_socket(vu_dev);
\tif (rc)
\t\tgoto error_init;

\tplatform_set_drvdata(pdev, vu_dev);
"""
        s = replace_once(s, old, new, "GPU socket probe hook")

    source.write_text(s)

    # Fail loudly if an upstream update makes the patch only partially apply.
    checks = [
        "#include <linux/virtio_ids.h>",
        "VHOST_USER_GPU_SET_SOCKET",
        helper_marker,
        "Vessel vhost-user-gpu display relay attached",
        "vessel_vhost_user_set_gpu_socket(vu_dev);",
    ]
    final_h = header.read_text()
    final_s = source.read_text()
    for needle in checks:
        if needle not in final_h and needle not in final_s:
            raise SystemExit(f"patch verification failed: {needle}")

    print("Vessel UML vhost-user-gpu display socket handoff patched")


if __name__ == "__main__":
    main()
