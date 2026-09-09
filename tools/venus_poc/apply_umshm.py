#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "linux-um-arm64")


def replace_once(path: Path, old: str, new: str):
    text = path.read_text()
    if old not in text:
        raise SystemExit(f"expected source block not found in {path}")
    if text.count(old) != 1:
        raise SystemExit(f"expected exactly one source block in {path}, found {text.count(old)}")
    path.write_text(text.replace(old, new, 1))


makefile = root / "arch/um/drivers/Makefile"
replace_once(
    makefile,
    "obj-$(CONFIG_UML_PCI_OVER_VFIO) += vfio_uml.o\n\n# pcap_user.o must be added explicitly.\n",
    "obj-$(CONFIG_UML_PCI_OVER_VFIO) += vfio_uml.o\nobj-y += umshm_kern.o\n\n# pcap_user.o must be added explicitly.\n",
)

header = root / "arch/um/include/shared/umshm.h"
header.write_text(r'''/* SPDX-License-Identifier: GPL-2.0 */
#ifndef __UM_UML_SHM_H
#define __UM_UML_SHM_H

#include <linux/types.h>

#define UML_SHM_OP_REGISTER 0
#define UML_SHM_OP_UNREGISTER 1

int uml_shm_register(__u32 id, int host_fd, unsigned long len,
                     unsigned long *phys_out);
int uml_shm_lookup(__u32 id, unsigned long *phys_out,
                   unsigned long *len_out);
int uml_shm_get(__u32 id, unsigned long *phys_out,
                unsigned long *len_out);
void uml_shm_put(__u32 id);
int uml_shm_unregister(__u32 id);

struct uml_shm_host_msg {
    __u32 id;
    __u32 reserved;
    __u64 size;
};

#endif
''')

phys = root / "arch/um/kernel/physmem.c"
replace_once(
    phys,
    "#include <linux/pfn.h>\n#include <asm/page.h>\n",
    "#include <linux/pfn.h>\n#include <linux/sizes.h>\n#include <linux/spinlock.h>\n#include <asm/page.h>\n",
)
replace_once(
    phys,
    "#include <os.h>\n\nstatic int physmem_fd = -1;\n",
    "#include <os.h>\n#include <umshm.h>\n\nstatic int physmem_fd = -1;\n",
)
registry = r'''
#define UML_SHM_MAX_MAPS 16

struct uml_shm_map {
    bool used;
    bool pending_remove;
    __u32 id;
    int fd;
    unsigned int users;
    unsigned long phys;
    unsigned long len;
};

static struct uml_shm_map uml_shm_maps[UML_SHM_MAX_MAPS];
static DEFINE_SPINLOCK(uml_shm_lock);
static unsigned long uml_shm_next_phys;

int uml_shm_register(__u32 id, int host_fd, unsigned long len,
                     unsigned long *phys_out)
{
    unsigned long flags;
    unsigned long rounded = PAGE_ALIGN(len);
    int i, free_slot = -1;

    if (!id || host_fd < 0 || !len || !phys_out)
        return -EINVAL;

    spin_lock_irqsave(&uml_shm_lock, flags);
    if (!uml_shm_next_phys)
        uml_shm_next_phys = PAGE_ALIGN(physmem_size + SZ_1G);

    for (i = 0; i < UML_SHM_MAX_MAPS; i++) {
        if (uml_shm_maps[i].used && uml_shm_maps[i].id == id) {
            spin_unlock_irqrestore(&uml_shm_lock, flags);
            return -EEXIST;
        }
        if (!uml_shm_maps[i].used && free_slot < 0)
            free_slot = i;
    }

    if (free_slot < 0) {
        spin_unlock_irqrestore(&uml_shm_lock, flags);
        return -ENOSPC;
    }

    uml_shm_maps[free_slot].used = true;
    uml_shm_maps[free_slot].pending_remove = false;
    uml_shm_maps[free_slot].id = id;
    uml_shm_maps[free_slot].fd = host_fd;
    uml_shm_maps[free_slot].users = 0;
    uml_shm_maps[free_slot].phys = uml_shm_next_phys;
    uml_shm_maps[free_slot].len = rounded;
    *phys_out = uml_shm_next_phys;
    uml_shm_next_phys += rounded;

    spin_unlock_irqrestore(&uml_shm_lock, flags);
    return 0;
}
EXPORT_SYMBOL_GPL(uml_shm_register);

int uml_shm_lookup(__u32 id, unsigned long *phys_out,
                   unsigned long *len_out)
{
    unsigned long flags;
    int i, ret = -ENOENT;

    spin_lock_irqsave(&uml_shm_lock, flags);
    for (i = 0; i < UML_SHM_MAX_MAPS; i++) {
        if (!uml_shm_maps[i].used || uml_shm_maps[i].id != id)
            continue;
        *phys_out = uml_shm_maps[i].phys;
        *len_out = uml_shm_maps[i].len;
        ret = 0;
        break;
    }
    spin_unlock_irqrestore(&uml_shm_lock, flags);
    return ret;
}
EXPORT_SYMBOL_GPL(uml_shm_lookup);

int uml_shm_get(__u32 id, unsigned long *phys_out,
                unsigned long *len_out)
{
    unsigned long flags;
    int i, ret = -ENOENT;

    spin_lock_irqsave(&uml_shm_lock, flags);
    for (i = 0; i < UML_SHM_MAX_MAPS; i++) {
        struct uml_shm_map *m = &uml_shm_maps[i];

        if (!m->used || m->id != id)
            continue;
        if (m->pending_remove) {
            ret = -ENOENT;
            break;
        }
        m->users++;
        *phys_out = m->phys;
        *len_out = m->len;
        ret = 0;
        break;
    }
    spin_unlock_irqrestore(&uml_shm_lock, flags);
    return ret;
}
EXPORT_SYMBOL_GPL(uml_shm_get);

void uml_shm_put(__u32 id)
{
    unsigned long flags;
    int close_fd = -1;
    int i;

    spin_lock_irqsave(&uml_shm_lock, flags);
    for (i = 0; i < UML_SHM_MAX_MAPS; i++) {
        struct uml_shm_map *m = &uml_shm_maps[i];

        if (!m->used || m->id != id)
            continue;
        if (WARN_ON(!m->users))
            break;
        m->users--;
        if (!m->users && m->pending_remove) {
            close_fd = m->fd;
            memset(m, 0, sizeof(*m));
        }
        break;
    }
    spin_unlock_irqrestore(&uml_shm_lock, flags);

    if (close_fd >= 0)
        os_close_file(close_fd);
}
EXPORT_SYMBOL_GPL(uml_shm_put);

int uml_shm_unregister(__u32 id)
{
    unsigned long flags;
    int close_fd = -1;
    int i, ret = -ENOENT;

    spin_lock_irqsave(&uml_shm_lock, flags);
    for (i = 0; i < UML_SHM_MAX_MAPS; i++) {
        struct uml_shm_map *m = &uml_shm_maps[i];

        if (!m->used || m->id != id)
            continue;
        if (m->users) {
            m->pending_remove = true;
        } else {
            close_fd = m->fd;
            memset(m, 0, sizeof(*m));
        }
        ret = 0;
        break;
    }
    spin_unlock_irqrestore(&uml_shm_lock, flags);

    if (close_fd >= 0)
        os_close_file(close_fd);
    return ret;
}
EXPORT_SYMBOL_GPL(uml_shm_unregister);

'''
replace_once(
    phys,
    "/* Changed during early boot */\nunsigned long high_physmem;\nEXPORT_SYMBOL(high_physmem);\n\nvoid map_memory",
    "/* Changed during early boot */\nunsigned long high_physmem;\nEXPORT_SYMBOL(high_physmem);\n\n" + registry + "void map_memory",
)
old_phys_mapping = r'''int phys_mapping(unsigned long phys, unsigned long long *offset_out)
{
\tint fd = -1;

\tif (phys < physmem_size) {
\t\tfd = physmem_fd;
\t\t*offset_out = phys;
\t}

\treturn fd;
}'''
new_phys_mapping = r'''int phys_mapping(unsigned long phys, unsigned long long *offset_out)
{
    int fd = -1;
    unsigned long flags;
    int i;

    if (phys < physmem_size) {
        fd = physmem_fd;
        *offset_out = phys;
        return fd;
    }

    spin_lock_irqsave(&uml_shm_lock, flags);
    for (i = 0; i < UML_SHM_MAX_MAPS; i++) {
        struct uml_shm_map *m = &uml_shm_maps[i];

        if (!m->used || phys < m->phys || phys >= m->phys + m->len)
            continue;

        fd = m->fd;
        *offset_out = phys - m->phys;
        break;
    }
    spin_unlock_irqrestore(&uml_shm_lock, flags);

    return fd;
}'''
replace_once(phys, old_phys_mapping, new_phys_mapping)

driver = root / "arch/um/drivers/umshm_kern.c"
driver.write_text(r'''// SPDX-License-Identifier: GPL-2.0
/* Direct host memfd -> UML guest userspace bridge, initially for Venus. */

#include <linux/delay.h>
#include <linux/fs.h>
#include <linux/kthread.h>
#include <linux/limits.h>
#include <linux/miscdevice.h>
#include <linux/mm.h>
#include <linux/module.h>
#include <linux/string.h>
#include <linux/uaccess.h>
#include <os.h>
#include <shared/init.h>
#include <umshm.h>

#define UML_SHM_PATH_MAX 108

struct uml_shm_ack {
    __u32 id;
    __s32 status;
};

static char uml_shm_socket[UML_SHM_PATH_MAX];
static struct task_struct *uml_shm_thread;

static int uml_shm_arg(char *line, int *add)
{
    *add = 0;
    strscpy(uml_shm_socket, line, sizeof(uml_shm_socket));
    return 0;
}

__uml_setup("umshm_sock=", uml_shm_arg,
"umshm_sock=<host unix socket>\n"
"    Receive host memfds for /dev/umshm mappings.\n\n"
);

static int uml_shm_control(void *unused)
{
    int sock = -1;

    while (!kthread_should_stop()) {
        if (sock < 0) {
            if (!uml_shm_socket[0])
                return 0;
            sock = os_connect_socket(uml_shm_socket);
            if (sock < 0) {
                msleep(100);
                continue;
            }
            pr_info("umshm: connected to host control socket %s\n", uml_shm_socket);
        }

        for (;;) {
            struct uml_shm_host_msg msg;
            struct uml_shm_ack ack;
            unsigned long phys = 0;
            int fd = -1;
            ssize_t n;
            int ret;

            if (kthread_should_stop())
                goto out;

            n = os_rcv_fd_msg(sock, &fd, 1, &msg, sizeof(msg));
            if (n <= 0) {
                os_close_file(sock);
                sock = -1;
                break;
            }
            if (n != sizeof(msg)) {
                if (fd >= 0)
                    os_close_file(fd);
                continue;
            }

            if (msg.reserved == UML_SHM_OP_UNREGISTER) {
                if (fd >= 0)
                    os_close_file(fd);
                ret = uml_shm_unregister(msg.id);
                if (!ret)
                    pr_info("umshm: unregister id=%u\n", msg.id);
            } else if (msg.reserved != UML_SHM_OP_REGISTER || fd < 0) {
                if (fd >= 0)
                    os_close_file(fd);
                ret = -EINVAL;
            } else if (!msg.size || msg.size > (u64)ULONG_MAX) {
                os_close_file(fd);
                ret = -EINVAL;
            } else {
                ret = uml_shm_register(msg.id, fd, (unsigned long)msg.size, &phys);
                if (ret)
                    os_close_file(fd);
                else
                    pr_info("umshm: id=%u size=%llu phys=0x%lx fd=%d\n",
                            msg.id, (unsigned long long)msg.size, phys, fd);
            }

            ack.id = msg.id;
            ack.status = ret;
            os_write_file(sock, &ack, sizeof(ack));
        }
    }
out:
    if (sock >= 0)
        os_close_file(sock);
    return 0;
}

static int uml_shm_open(struct inode *inode, struct file *file)
{
    file->private_data = NULL;
    return 0;
}

static ssize_t uml_shm_write(struct file *file, const char __user *buf,
                             size_t len, loff_t *ppos)
{
    __u32 id;
    unsigned long phys, map_len;
    int ret;

    if (len != sizeof(id))
        return -EINVAL;
    if (file->private_data)
        return -EBUSY;
    if (copy_from_user(&id, buf, sizeof(id)))
        return -EFAULT;

    ret = uml_shm_get(id, &phys, &map_len);
    if (ret)
        return ret;

    file->private_data = (void *)(unsigned long)id;
    return sizeof(id);
}

static int uml_shm_release(struct inode *inode, struct file *file)
{
    __u32 id = (__u32)(unsigned long)file->private_data;

    if (id)
        uml_shm_put(id);
    return 0;
}

static int uml_shm_mmap(struct file *file, struct vm_area_struct *vma)
{
    __u32 id = (__u32)(unsigned long)file->private_data;
    unsigned long phys, map_len;
    unsigned long len = vma->vm_end - vma->vm_start;
    int ret;

    if (!id)
        return -EINVAL;

    ret = uml_shm_lookup(id, &phys, &map_len);
    if (ret)
        return ret;
    if (vma->vm_pgoff || !len || len > map_len)
        return -EINVAL;

    vm_flags_set(vma, VM_PFNMAP | VM_DONTEXPAND | VM_DONTDUMP);
    return remap_pfn_range(vma, vma->vm_start, phys >> PAGE_SHIFT,
                           len, vma->vm_page_prot);
}

static const struct file_operations uml_shm_fops = {
    .owner = THIS_MODULE,
    .open = uml_shm_open,
    .release = uml_shm_release,
    .write = uml_shm_write,
    .mmap = uml_shm_mmap,
    .llseek = noop_llseek,
};

static struct miscdevice uml_shm_misc = {
    .minor = MISC_DYNAMIC_MINOR,
    .name = "umshm",
    .fops = &uml_shm_fops,
    .mode = 0666,
};

static int __init uml_shm_init(void)
{
    int ret;

    ret = misc_register(&uml_shm_misc);
    if (ret)
        return ret;

    if (uml_shm_socket[0]) {
        uml_shm_thread = kthread_run(uml_shm_control, NULL, "umshm-host");
        if (IS_ERR(uml_shm_thread)) {
            ret = PTR_ERR(uml_shm_thread);
            misc_deregister(&uml_shm_misc);
            return ret;
        }
    }

    pr_info("umshm: /dev/umshm ready\n");
    return 0;
}
device_initcall(uml_shm_init);
''')

print("umshm source transform applied")
