#define _GNU_SOURCE
#include <wayland-server-core.h>
#include <wayland-server-protocol.h>
#include <xkbcommon/xkbcommon.h>
#include "xdg-shell-server-protocol.h"
#include "linux-dmabuf-unstable-v1-server-protocol.h"
#include "presentation-time-server-protocol.h"

#include <errno.h>
#include <fcntl.h>
#include <getopt.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define VESSEL_FRAME_MAGIC 0x31574656u
#define VESSEL_IMPORT 1u
#define VESSEL_FRAME 2u
#define VESSEL_SHM_DAMAGE 4u
#define VESSEL_INPUT_MAGIC 0x31534956u
#define VINPUT_ABS 1u
#define VINPUT_REL 2u
#define VINPUT_BUTTON 3u
#define VINPUT_SCROLL 4u
#define VINPUT_KEY 5u
#define DRM_FORMAT_MOD_LINEAR 0ULL
#define DRM_FORMAT_MOD_INVALID 0x00ffffffffffffffULL

struct bridge;
struct surface;

#pragma pack(push, 1)
struct vessel_frame_msg {
    uint32_t magic, type, width, height, fourcc, stride, offset, reserved;
    uint64_t modifier, serial;
};
struct vessel_input_msg {
    uint32_t magic, type;
    int32_t a, b, c, d;
};
#pragma pack(pop)

struct vessel_buffer {
    struct bridge *bridge;
    struct wl_resource *resource;
    int fd;
    uint32_t width, height, format, stride, offset;
    uint64_t modifier, serial;
    int imported;
};

struct dmabuf_params {
    struct bridge *bridge;
    struct wl_resource *resource;
    int fd;
    uint32_t stride, offset;
    uint64_t modifier;
    int have_plane0;
};

struct frame_callback {
    struct wl_list link;
    struct wl_resource *resource;
};

struct presentation_feedback {
    struct wl_list link;
    struct wl_resource *resource;
};

struct surface {
    struct wl_list link;
    struct bridge *bridge;
    struct wl_resource *resource;
    struct vessel_buffer *pending_dmabuf;
    struct wl_resource *pending_shm_resource;
    struct vessel_buffer *current_dmabuf;
    struct wl_resource *current_shm_resource;
    struct wl_list callbacks;
    struct wl_list feedbacks;
    int32_t buffer_scale;
    int pending_attached;
    int is_toplevel;
    int is_subsurface;
    int mapped;
    int damage_valid;
    int32_t damage_x1, damage_y1, damage_x2, damage_y2;
};

struct input_resource {
    struct wl_list link;
    struct wl_resource *resource;
    struct bridge *bridge;
};

struct bridge {
    struct wl_display *display;
    struct wl_event_loop *loop;
    struct wl_event_source *frame_timer;
    struct wl_list surfaces;
    struct wl_list pointers;
    struct wl_list keyboards;
    struct wl_list touches;
    const char *socket_name;
    const char *frame_socket;
    const char *input_socket;
    int width, height, refresh_mhz;
    int frame_fd;
    int frame_timer_armed;
    uint64_t next_buffer_serial;
    uint64_t presentation_seq;
    struct surface *focus;
    double pointer_x, pointer_y;
    int touch_down;
    int input_listener_fd;
    int input_client_fd;
    struct wl_event_source *input_listener_source;
    struct wl_event_source *input_client_source;
    struct xkb_context *xkb_context;
    struct xkb_keymap *xkb_keymap;
    struct xkb_state *xkb_state;
    char *keymap_text;
    size_t keymap_size;
};

static struct bridge *g_bridge;

static uint32_t fourcc(char a, char b, char c, char d) {
    return (uint32_t)a | ((uint32_t)b << 8) | ((uint32_t)c << 16) | ((uint32_t)d << 24);
}

static uint32_t monotonic_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint32_t)((uint64_t)ts.tv_sec * 1000u + (uint64_t)ts.tv_nsec / 1000000u);
}

static void die(const char *what) {
    fprintf(stderr, "[vessel-host] %s: %s\n", what, strerror(errno));
    exit(1);
}

static int send_all(int fd, const void *data, size_t len) {
    const uint8_t *p = data;
    while (len) {
        ssize_t n = send(fd, p, len, MSG_NOSIGNAL);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return -1;
        p += n;
        len -= (size_t)n;
    }
    return 0;
}

static void disconnect_frame(struct bridge *b) {
    if (b->frame_fd >= 0) close(b->frame_fd);
    b->frame_fd = -1;
}

static int connect_frame(struct bridge *b) {
    if (b->frame_fd >= 0) return 0;
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    struct sockaddr_un addr = {0};
    addr.sun_family = AF_UNIX;
    if (strlen(b->frame_socket) >= sizeof(addr.sun_path)) {
        close(fd);
        errno = ENAMETOOLONG;
        return -1;
    }
    strncpy(addr.sun_path, b->frame_socket, sizeof(addr.sun_path) - 1);
    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        close(fd);
        return -1;
    }
    b->frame_fd = fd;
    fprintf(stderr, "[vessel-host] connected frame transport %s\n", b->frame_socket);
    return 0;
}

static int send_import(struct vessel_buffer *buf) {
    struct bridge *b = buf->bridge;
    if (connect_frame(b) < 0) return -1;
    struct vessel_frame_msg msg = {
        .magic = VESSEL_FRAME_MAGIC, .type = VESSEL_IMPORT,
        .width = buf->width, .height = buf->height, .fourcc = buf->format,
        .stride = buf->stride, .offset = buf->offset,
        .modifier = buf->modifier, .serial = buf->serial,
    };
    struct iovec iov = { .iov_base = &msg, .iov_len = sizeof(msg) };
    char control[CMSG_SPACE(sizeof(int))] = {0};
    struct msghdr hdr = {0};
    hdr.msg_iov = &iov;
    hdr.msg_iovlen = 1;
    hdr.msg_control = control;
    hdr.msg_controllen = sizeof(control);
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&hdr);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(cmsg), &buf->fd, sizeof(int));
    ssize_t n = sendmsg(b->frame_fd, &hdr, MSG_NOSIGNAL);
    if (n != (ssize_t)sizeof(msg)) {
        disconnect_frame(b);
        return -1;
    }
    buf->imported = 1;
    fprintf(stderr, "[vessel-host] dmabuf-import id=%llu %ux%u fourcc=%08x modifier=%llx\n",
            (unsigned long long)buf->serial, buf->width, buf->height, buf->format,
            (unsigned long long)buf->modifier);
    return 0;
}

static int send_dmabuf_frame(struct vessel_buffer *buf) {
    struct bridge *b = buf->bridge;
    if (!buf->imported && send_import(buf) < 0) return -1;
    struct vessel_frame_msg msg = {
        .magic = VESSEL_FRAME_MAGIC, .type = VESSEL_FRAME,
        .width = buf->width, .height = buf->height, .fourcc = buf->format,
        .stride = buf->stride, .offset = buf->offset,
        .modifier = buf->modifier, .serial = buf->serial,
    };
    if (send_all(b->frame_fd, &msg, sizeof(msg)) < 0) {
        disconnect_frame(b);
        buf->imported = 0;
        return -1;
    }
    return 0;
}

static uint32_t shm_format_to_fourcc(uint32_t format) {
    if (format == WL_SHM_FORMAT_ARGB8888) return fourcc('A','R','2','4');
    if (format == WL_SHM_FORMAT_XRGB8888) return fourcc('X','R','2','4');
    return format;
}

static void damage_reset(struct surface *s) {
    s->damage_valid = 0;
    s->damage_x1 = s->damage_y1 = s->damage_x2 = s->damage_y2 = 0;
}

static void damage_add(struct surface *s, int32_t x, int32_t y, int32_t w, int32_t h) {
    if (w <= 0 || h <= 0) return;
    int64_t x2 = (int64_t)x + w;
    int64_t y2 = (int64_t)y + h;
    if (!s->damage_valid) {
        s->damage_x1 = x; s->damage_y1 = y;
        s->damage_x2 = (int32_t)x2; s->damage_y2 = (int32_t)y2;
        s->damage_valid = 1;
    } else {
        if (x < s->damage_x1) s->damage_x1 = x;
        if (y < s->damage_y1) s->damage_y1 = y;
        if (x2 > s->damage_x2) s->damage_x2 = (int32_t)x2;
        if (y2 > s->damage_y2) s->damage_y2 = (int32_t)y2;
    }
}

static int send_shm_damage(struct surface *s, struct wl_resource *buffer_resource) {
    struct wl_shm_buffer *shm = wl_shm_buffer_get(buffer_resource);
    if (!shm) return -1;
    int width = wl_shm_buffer_get_width(shm);
    int height = wl_shm_buffer_get_height(shm);
    int stride = wl_shm_buffer_get_stride(shm);
    uint32_t format = wl_shm_buffer_get_format(shm);
    if (width <= 0 || height <= 0 || stride <= 0) return -1;
    uint32_t out_format = shm_format_to_fourcc(format);
    if (out_format != fourcc('A','R','2','4') && out_format != fourcc('X','R','2','4')) {
        fprintf(stderr, "[vessel-host] unsupported SHM format %08x\n", format);
        return -1;
    }

    int x1 = s->damage_valid ? s->damage_x1 : 0;
    int y1 = s->damage_valid ? s->damage_y1 : 0;
    int x2 = s->damage_valid ? s->damage_x2 : width;
    int y2 = s->damage_valid ? s->damage_y2 : height;
    if (x1 < 0) x1 = 0;
    if (y1 < 0) y1 = 0;
    if (x2 > width) x2 = width;
    if (y2 > height) y2 = height;
    if (x2 <= x1 || y2 <= y1) return 0;
    int dw = x2 - x1;
    int dh = y2 - y1;
    uint32_t payload_stride = (uint32_t)dw * 4u;

    struct bridge *b = s->bridge;
    if (connect_frame(b) < 0) return -1;
    struct vessel_frame_msg msg = {
        .magic = VESSEL_FRAME_MAGIC, .type = VESSEL_SHM_DAMAGE,
        .width = (uint32_t)width, .height = (uint32_t)height,
        .fourcc = out_format, .stride = payload_stride,
        .offset = (uint32_t)x1, .reserved = (uint32_t)y1,
        .modifier = ((uint64_t)(uint32_t)dh << 32u) | (uint32_t)dw,
        .serial = 0,
    };
    if (send_all(b->frame_fd, &msg, sizeof(msg)) < 0) {
        disconnect_frame(b);
        return -1;
    }

    wl_shm_buffer_begin_access(shm);
    const uint8_t *base = wl_shm_buffer_get_data(shm);
    for (int row = 0; row < dh; ++row) {
        const uint8_t *src = base + (size_t)(y1 + row) * (size_t)stride + (size_t)x1 * 4u;
        if (send_all(b->frame_fd, src, payload_stride) < 0) {
            wl_shm_buffer_end_access(shm);
            disconnect_frame(b);
            return -1;
        }
    }
    wl_shm_buffer_end_access(shm);
    fprintf(stderr, "[vessel-host] shm-damage %dx%d+%d+%d output=%dx%d bytes=%u\n",
            dw, dh, x1, y1, width, height, payload_stride * (uint32_t)dh);
    return 0;
}

static void callback_destroy_resource(struct wl_resource *resource) {
    struct frame_callback *cb = wl_resource_get_user_data(resource);
    if (!cb) return;
    wl_list_remove(&cb->link);
    free(cb);
}

static void feedback_destroy_resource(struct wl_resource *resource) {
    struct presentation_feedback *fb = wl_resource_get_user_data(resource);
    if (!fb) return;
    wl_list_remove(&fb->link);
    free(fb);
}

static void presentation_feedback_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}
static const struct wp_presentation_feedback_interface feedback_impl = {
    .destroy = presentation_feedback_destroy,
};

static int frame_timer_handler(void *data) {
    struct bridge *b = data;
    b->frame_timer_armed = 0;
    uint32_t now_ms = monotonic_ms();
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    uint64_t seq = ++b->presentation_seq;
    uint32_t refresh_ns = b->refresh_mhz > 0 ? (uint32_t)(1000000000000ULL / (uint64_t)b->refresh_mhz) : 16666667u;
    struct surface *s;
    wl_list_for_each(s, &b->surfaces, link) {
        struct frame_callback *cb, *cbtmp;
        wl_list_for_each_safe(cb, cbtmp, &s->callbacks, link) {
            wl_callback_send_done(cb->resource, now_ms);
            wl_resource_destroy(cb->resource);
        }
        struct presentation_feedback *fb, *fbtmp;
        wl_list_for_each_safe(fb, fbtmp, &s->feedbacks, link) {
            uint64_t sec = (uint64_t)now.tv_sec;
            wp_presentation_feedback_send_presented(
                fb->resource,
                (uint32_t)(sec >> 32u), (uint32_t)sec, (uint32_t)now.tv_nsec,
                refresh_ns, (uint32_t)(seq >> 32u), (uint32_t)seq, 0);
            wl_resource_destroy(fb->resource);
        }
    }
    return 0;
}

static void schedule_frame(struct bridge *b) {
    if (b->frame_timer_armed || !b->frame_timer) return;
    int delay_ms = b->refresh_mhz > 0 ? (1000000 + b->refresh_mhz - 1) / b->refresh_mhz : 17;
    if (delay_ms < 1) delay_ms = 1;
    wl_event_source_timer_update(b->frame_timer, delay_ms);
    b->frame_timer_armed = 1;
}

static void buffer_destroy_resource(struct wl_resource *resource) {
    struct vessel_buffer *buf = wl_resource_get_user_data(resource);
    if (!buf) return;
    if (buf->fd >= 0) close(buf->fd);
    free(buf);
}
static void buffer_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}
static const struct wl_buffer_interface buffer_impl = { .destroy = buffer_destroy };

static struct wl_resource *create_dmabuf_buffer(struct dmabuf_params *p, struct wl_client *client,
                                                 uint32_t id, int32_t width, int32_t height,
                                                 uint32_t format) {
    if (!p->have_plane0 || p->fd < 0 || width <= 0 || height <= 0) return NULL;
    struct vessel_buffer *buf = calloc(1, sizeof(*buf));
    if (!buf) return NULL;
    buf->bridge = p->bridge;
    buf->fd = p->fd;
    p->fd = -1;
    buf->width = (uint32_t)width;
    buf->height = (uint32_t)height;
    buf->format = format;
    buf->stride = p->stride;
    buf->offset = p->offset;
    buf->modifier = p->modifier;
    buf->serial = ++p->bridge->next_buffer_serial;
    struct wl_resource *res = wl_resource_create(client, &wl_buffer_interface, 1, id);
    if (!res) {
        close(buf->fd);
        free(buf);
        return NULL;
    }
    buf->resource = res;
    wl_resource_set_implementation(res, &buffer_impl, buf, buffer_destroy_resource);
    return res;
}

static void params_destroy_resource(struct wl_resource *resource) {
    struct dmabuf_params *p = wl_resource_get_user_data(resource);
    if (!p) return;
    if (p->fd >= 0) close(p->fd);
    free(p);
}
static void params_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}
static void params_add(struct wl_client *client, struct wl_resource *resource, int32_t fd,
                       uint32_t plane_idx, uint32_t offset, uint32_t stride,
                       uint32_t modifier_hi, uint32_t modifier_lo) {
    (void)client;
    struct dmabuf_params *p = wl_resource_get_user_data(resource);
    if (!p || plane_idx != 0 || p->have_plane0) {
        close(fd);
        return;
    }
    p->fd = fd;
    p->offset = offset;
    p->stride = stride;
    p->modifier = ((uint64_t)modifier_hi << 32u) | modifier_lo;
    p->have_plane0 = 1;
}
static void params_create(struct wl_client *client, struct wl_resource *resource,
                          int32_t width, int32_t height, uint32_t format, uint32_t flags) {
    (void)flags;
    struct dmabuf_params *p = wl_resource_get_user_data(resource);
    struct wl_resource *buffer = create_dmabuf_buffer(p, client, 0, width, height, format);
    if (!buffer) zwp_linux_buffer_params_v1_send_failed(resource);
    else zwp_linux_buffer_params_v1_send_created(resource, buffer);
}
static void params_create_immed(struct wl_client *client, struct wl_resource *resource,
                                uint32_t id, int32_t width, int32_t height,
                                uint32_t format, uint32_t flags) {
    (void)flags;
    struct dmabuf_params *p = wl_resource_get_user_data(resource);
    if (!create_dmabuf_buffer(p, client, id, width, height, format)) {
        wl_resource_post_error(resource, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INVALID_WL_BUFFER,
                               "Vessel requires a valid single-plane dma-buf");
    }
}
static const struct zwp_linux_buffer_params_v1_interface params_impl = {
    .destroy = params_destroy, .add = params_add,
    .create = params_create, .create_immed = params_create_immed,
};
static void dmabuf_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client; wl_resource_destroy(resource);
}
static void dmabuf_create_params(struct wl_client *client, struct wl_resource *resource, uint32_t id) {
    struct bridge *b = wl_resource_get_user_data(resource);
    struct dmabuf_params *p = calloc(1, sizeof(*p));
    if (!p) { wl_client_post_no_memory(client); return; }
    p->bridge = b; p->fd = -1;
    p->resource = wl_resource_create(client, &zwp_linux_buffer_params_v1_interface,
                                     wl_resource_get_version(resource), id);
    if (!p->resource) { free(p); wl_client_post_no_memory(client); return; }
    wl_resource_set_implementation(p->resource, &params_impl, p, params_destroy_resource);
}
static const struct zwp_linux_dmabuf_v1_interface dmabuf_impl = {
    .destroy = dmabuf_destroy, .create_params = dmabuf_create_params,
    .get_default_feedback = NULL, .get_surface_feedback = NULL,
};
static void send_dmabuf_caps(struct wl_resource *res, uint32_t format) {
    if (wl_resource_get_version(res) >= 3) {
        zwp_linux_dmabuf_v1_send_modifier(res, format,
            (uint32_t)(DRM_FORMAT_MOD_LINEAR >> 32u), (uint32_t)DRM_FORMAT_MOD_LINEAR);
        zwp_linux_dmabuf_v1_send_modifier(res, format,
            (uint32_t)(DRM_FORMAT_MOD_INVALID >> 32u), (uint32_t)DRM_FORMAT_MOD_INVALID);
    } else {
        zwp_linux_dmabuf_v1_send_format(res, format);
    }
}
static void bind_dmabuf(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
    struct bridge *b = data;
    uint32_t v = version > 3 ? 3 : version;
    struct wl_resource *res = wl_resource_create(client, &zwp_linux_dmabuf_v1_interface, v, id);
    if (!res) { wl_client_post_no_memory(client); return; }
    wl_resource_set_implementation(res, &dmabuf_impl, b, NULL);
    send_dmabuf_caps(res, fourcc('X','R','2','4'));
    send_dmabuf_caps(res, fourcc('A','R','2','4'));
    send_dmabuf_caps(res, fourcc('X','B','2','4'));
    send_dmabuf_caps(res, fourcc('A','B','2','4'));
}

static void input_resource_destroy(struct wl_resource *resource) {
    struct input_resource *ir = wl_resource_get_user_data(resource);
    if (!ir) return;
    wl_list_remove(&ir->link);
    free(ir);
}

static void send_keyboard_modifiers(struct bridge *b) {
    if (!b->xkb_state) return;
    uint32_t depressed = xkb_state_serialize_mods(b->xkb_state, XKB_STATE_MODS_DEPRESSED);
    uint32_t latched = xkb_state_serialize_mods(b->xkb_state, XKB_STATE_MODS_LATCHED);
    uint32_t locked = xkb_state_serialize_mods(b->xkb_state, XKB_STATE_MODS_LOCKED);
    uint32_t group = xkb_state_serialize_layout(b->xkb_state, XKB_STATE_LAYOUT_EFFECTIVE);
    uint32_t serial = wl_display_next_serial(b->display);
    struct input_resource *ir;
    wl_list_for_each(ir, &b->keyboards, link) {
        wl_keyboard_send_modifiers(ir->resource, serial, depressed, latched, locked, group);
    }
}

static void focus_enter(struct bridge *b) {
    if (!b->focus || !b->focus->resource || !b->focus->mapped) return;
    uint32_t serial = wl_display_next_serial(b->display);
    wl_fixed_t x = wl_fixed_from_double(b->pointer_x);
    wl_fixed_t y = wl_fixed_from_double(b->pointer_y);
    struct input_resource *ir;
    wl_list_for_each(ir, &b->pointers, link) {
        wl_pointer_send_enter(ir->resource, serial, b->focus->resource, x, y);
        if (wl_resource_get_version(ir->resource) >= WL_POINTER_FRAME_SINCE_VERSION)
            wl_pointer_send_frame(ir->resource);
    }
    struct wl_array keys;
    wl_array_init(&keys);
    wl_list_for_each(ir, &b->keyboards, link) {
        wl_keyboard_send_enter(ir->resource, serial, b->focus->resource, &keys);
    }
    wl_array_release(&keys);
    send_keyboard_modifiers(b);
}

static void pointer_set_cursor(struct wl_client *client, struct wl_resource *resource, uint32_t serial,
                               struct wl_resource *surface, int32_t hotspot_x, int32_t hotspot_y) {
    (void)client; (void)resource; (void)serial; (void)surface; (void)hotspot_x; (void)hotspot_y;
    /* Android draws a local low-latency cursor in trackpad mode. */
}
static void pointer_release(struct wl_client *client, struct wl_resource *resource) {
    (void)client; wl_resource_destroy(resource);
}
static const struct wl_pointer_interface pointer_impl = {
    .set_cursor = pointer_set_cursor, .release = pointer_release,
};
static void keyboard_release(struct wl_client *client, struct wl_resource *resource) {
    (void)client; wl_resource_destroy(resource);
}
static const struct wl_keyboard_interface keyboard_impl = { .release = keyboard_release };
static void touch_release(struct wl_client *client, struct wl_resource *resource) {
    (void)client; wl_resource_destroy(resource);
}
static const struct wl_touch_interface touch_impl = { .release = touch_release };

static void seat_get_pointer(struct wl_client *client, struct wl_resource *resource, uint32_t id) {
    struct bridge *b = wl_resource_get_user_data(resource);
    struct input_resource *ir = calloc(1, sizeof(*ir));
    if (!ir) { wl_client_post_no_memory(client); return; }
    uint32_t v = wl_resource_get_version(resource);
    ir->bridge = b;
    ir->resource = wl_resource_create(client, &wl_pointer_interface, v, id);
    if (!ir->resource) { free(ir); wl_client_post_no_memory(client); return; }
    wl_list_insert(&b->pointers, &ir->link);
    wl_resource_set_implementation(ir->resource, &pointer_impl, ir, input_resource_destroy);
    if (b->focus && b->focus->mapped) focus_enter(b);
}

static int make_keymap_fd(const char *text, size_t size) {
    int fd = memfd_create("vessel-keymap", MFD_CLOEXEC);
    if (fd < 0) return -1;
    if (ftruncate(fd, (off_t)size) < 0) { close(fd); return -1; }
    ssize_t written = 0;
    while ((size_t)written < size) {
        ssize_t n = pwrite(fd, text + written, size - (size_t)written, written);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { close(fd); return -1; }
        written += n;
    }
    return fd;
}

static void seat_get_keyboard(struct wl_client *client, struct wl_resource *resource, uint32_t id) {
    struct bridge *b = wl_resource_get_user_data(resource);
    struct input_resource *ir = calloc(1, sizeof(*ir));
    if (!ir) { wl_client_post_no_memory(client); return; }
    uint32_t v = wl_resource_get_version(resource);
    ir->bridge = b;
    ir->resource = wl_resource_create(client, &wl_keyboard_interface, v, id);
    if (!ir->resource) { free(ir); wl_client_post_no_memory(client); return; }
    wl_list_insert(&b->keyboards, &ir->link);
    wl_resource_set_implementation(ir->resource, &keyboard_impl, ir, input_resource_destroy);
    int fd = make_keymap_fd(b->keymap_text, b->keymap_size);
    if (fd >= 0) {
        wl_keyboard_send_keymap(ir->resource, WL_KEYBOARD_KEYMAP_FORMAT_XKB_V1, fd, (uint32_t)b->keymap_size);
        close(fd);
    }
    if (v >= WL_KEYBOARD_REPEAT_INFO_SINCE_VERSION) wl_keyboard_send_repeat_info(ir->resource, 40, 400);
    if (b->focus && b->focus->mapped) focus_enter(b);
}

static void seat_get_touch(struct wl_client *client, struct wl_resource *resource, uint32_t id) {
    struct bridge *b = wl_resource_get_user_data(resource);
    struct input_resource *ir = calloc(1, sizeof(*ir));
    if (!ir) { wl_client_post_no_memory(client); return; }
    uint32_t v = wl_resource_get_version(resource);
    ir->bridge = b;
    ir->resource = wl_resource_create(client, &wl_touch_interface, v, id);
    if (!ir->resource) { free(ir); wl_client_post_no_memory(client); return; }
    wl_list_insert(&b->touches, &ir->link);
    wl_resource_set_implementation(ir->resource, &touch_impl, ir, input_resource_destroy);
}
static void seat_release(struct wl_client *client, struct wl_resource *resource) {
    (void)client; wl_resource_destroy(resource);
}
static const struct wl_seat_interface seat_impl = {
    .get_pointer = seat_get_pointer, .get_keyboard = seat_get_keyboard,
    .get_touch = seat_get_touch, .release = seat_release,
};
static void bind_seat(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
    struct bridge *b = data;
    uint32_t v = version > 7 ? 7 : version;
    struct wl_resource *res = wl_resource_create(client, &wl_seat_interface, v, id);
    if (!res) { wl_client_post_no_memory(client); return; }
    wl_resource_set_implementation(res, &seat_impl, b, NULL);
    wl_seat_send_capabilities(res, WL_SEAT_CAPABILITY_POINTER | WL_SEAT_CAPABILITY_KEYBOARD | WL_SEAT_CAPABILITY_TOUCH);
    if (v >= WL_SEAT_NAME_SINCE_VERSION) wl_seat_send_name(res, "vessel-seat0");
}

static void dispatch_input(struct bridge *b, const struct vessel_input_msg *m) {
    if (!b->focus || !b->focus->mapped) return;
    uint32_t now = monotonic_ms();
    struct input_resource *ir;
    if (m->type == VINPUT_ABS) {
        double nx = (double)m->a / 32767.0;
        double ny = (double)m->b / 32767.0;
        if (nx < 0) nx = 0; if (nx > 1) nx = 1;
        if (ny < 0) ny = 0; if (ny > 1) ny = 1;
        b->pointer_x = nx * b->width;
        b->pointer_y = ny * b->height;
        if (m->c && !b->touch_down) {
            uint32_t serial = wl_display_next_serial(b->display);
            wl_list_for_each(ir, &b->touches, link)
                wl_touch_send_down(ir->resource, serial, now, b->focus->resource, 0,
                                   wl_fixed_from_double(b->pointer_x), wl_fixed_from_double(b->pointer_y));
            b->touch_down = 1;
        } else if (m->c && b->touch_down) {
            wl_list_for_each(ir, &b->touches, link)
                wl_touch_send_motion(ir->resource, now, 0,
                                     wl_fixed_from_double(b->pointer_x), wl_fixed_from_double(b->pointer_y));
        } else if (!m->c && b->touch_down) {
            uint32_t serial = wl_display_next_serial(b->display);
            wl_list_for_each(ir, &b->touches, link) wl_touch_send_up(ir->resource, serial, now, 0);
            b->touch_down = 0;
        }
        wl_list_for_each(ir, &b->touches, link) {
            if (wl_resource_get_version(ir->resource) >= WL_TOUCH_FRAME_SINCE_VERSION) wl_touch_send_frame(ir->resource);
        }
    } else if (m->type == VINPUT_REL) {
        b->pointer_x += m->a;
        b->pointer_y += m->b;
        if (b->pointer_x < 0) b->pointer_x = 0;
        if (b->pointer_y < 0) b->pointer_y = 0;
        if (b->pointer_x > b->width - 1) b->pointer_x = b->width - 1;
        if (b->pointer_y > b->height - 1) b->pointer_y = b->height - 1;
        wl_list_for_each(ir, &b->pointers, link) {
            wl_pointer_send_motion(ir->resource, now, wl_fixed_from_double(b->pointer_x), wl_fixed_from_double(b->pointer_y));
            if (wl_resource_get_version(ir->resource) >= WL_POINTER_FRAME_SINCE_VERSION) wl_pointer_send_frame(ir->resource);
        }
    } else if (m->type == VINPUT_BUTTON) {
        uint32_t serial = wl_display_next_serial(b->display);
        wl_list_for_each(ir, &b->pointers, link) {
            wl_pointer_send_button(ir->resource, serial, now, (uint32_t)m->a,
                                   m->b ? WL_POINTER_BUTTON_STATE_PRESSED : WL_POINTER_BUTTON_STATE_RELEASED);
            if (wl_resource_get_version(ir->resource) >= WL_POINTER_FRAME_SINCE_VERSION) wl_pointer_send_frame(ir->resource);
        }
    } else if (m->type == VINPUT_SCROLL) {
        wl_list_for_each(ir, &b->pointers, link) {
            uint32_t v = wl_resource_get_version(ir->resource);
            if (v >= WL_POINTER_AXIS_SOURCE_SINCE_VERSION) wl_pointer_send_axis_source(ir->resource, WL_POINTER_AXIS_SOURCE_WHEEL);
            if (m->a) {
                wl_pointer_send_axis(ir->resource, now, WL_POINTER_AXIS_HORIZONTAL_SCROLL, wl_fixed_from_int(-m->a * 10));
                if (v >= WL_POINTER_AXIS_DISCRETE_SINCE_VERSION) wl_pointer_send_axis_discrete(ir->resource, WL_POINTER_AXIS_HORIZONTAL_SCROLL, -m->a);
            }
            if (m->b) {
                wl_pointer_send_axis(ir->resource, now, WL_POINTER_AXIS_VERTICAL_SCROLL, wl_fixed_from_int(-m->b * 10));
                if (v >= WL_POINTER_AXIS_DISCRETE_SINCE_VERSION) wl_pointer_send_axis_discrete(ir->resource, WL_POINTER_AXIS_VERTICAL_SCROLL, -m->b);
            }
            if (v >= WL_POINTER_FRAME_SINCE_VERSION) wl_pointer_send_frame(ir->resource);
        }
    } else if (m->type == VINPUT_KEY && m->a > 0 && m->a < 768) {
        enum xkb_key_direction dir = m->b ? XKB_KEY_DOWN : XKB_KEY_UP;
        xkb_state_update_key(b->xkb_state, (xkb_keycode_t)(m->a + 8), dir);
        uint32_t serial = wl_display_next_serial(b->display);
        wl_list_for_each(ir, &b->keyboards, link)
            wl_keyboard_send_key(ir->resource, serial, now, (uint32_t)m->a,
                                 m->b ? WL_KEYBOARD_KEY_STATE_PRESSED : WL_KEYBOARD_KEY_STATE_RELEASED);
        send_keyboard_modifiers(b);
    }
}

static int input_client_handler(int fd, uint32_t mask, void *data) {
    struct bridge *b = data;
    if (mask & (WL_EVENT_HANGUP | WL_EVENT_ERROR)) {
        if (b->input_client_source) wl_event_source_remove(b->input_client_source);
        b->input_client_source = NULL;
        if (b->input_client_fd >= 0) close(b->input_client_fd);
        b->input_client_fd = -1;
        return 0;
    }
    for (;;) {
        struct vessel_input_msg msg;
        ssize_t n = recv(fd, &msg, sizeof(msg), MSG_DONTWAIT);
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) break;
        if (n <= 0) break;
        if (n == (ssize_t)sizeof(msg) && msg.magic == VESSEL_INPUT_MAGIC) dispatch_input(b, &msg);
    }
    return 0;
}

static int input_listener_handler(int fd, uint32_t mask, void *data) {
    (void)mask;
    struct bridge *b = data;
    int client = accept4(fd, NULL, NULL, SOCK_CLOEXEC | SOCK_NONBLOCK);
    if (client < 0) return 0;
    if (b->input_client_source) wl_event_source_remove(b->input_client_source);
    if (b->input_client_fd >= 0) close(b->input_client_fd);
    b->input_client_fd = client;
    b->input_client_source = wl_event_loop_add_fd(b->loop, client,
        WL_EVENT_READABLE | WL_EVENT_HANGUP | WL_EVENT_ERROR, input_client_handler, b);
    fprintf(stderr, "[vessel-host] direct Android input connected\n");
    return 0;
}

static void setup_input_socket(struct bridge *b) {
    unlink(b->input_socket);
    int fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (fd < 0) die("input socket");
    struct sockaddr_un addr = {0};
    addr.sun_family = AF_UNIX;
    if (strlen(b->input_socket) >= sizeof(addr.sun_path)) die("input socket path");
    strncpy(addr.sun_path, b->input_socket, sizeof(addr.sun_path) - 1);
    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) die("input bind");
    chmod(b->input_socket, 0600);
    if (listen(fd, 1) < 0) die("input listen");
    b->input_listener_fd = fd;
    b->input_listener_source = wl_event_loop_add_fd(b->loop, fd, WL_EVENT_READABLE,
                                                     input_listener_handler, b);
    if (!b->input_listener_source) die("input event source");
}

static void surface_destroy_resource(struct wl_resource *resource) {
    struct surface *s = wl_resource_get_user_data(resource);
    if (!s) return;
    if (s->bridge->focus == s) s->bridge->focus = NULL;
    struct frame_callback *cb, *cbtmp;
    wl_list_for_each_safe(cb, cbtmp, &s->callbacks, link) wl_resource_destroy(cb->resource);
    struct presentation_feedback *fb, *fbtmp;
    wl_list_for_each_safe(fb, fbtmp, &s->feedbacks, link) wl_resource_destroy(fb->resource);
    wl_list_remove(&s->link);
    free(s);
}
static void surface_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client; wl_resource_destroy(resource);
}
static void surface_attach(struct wl_client *client, struct wl_resource *resource,
                           struct wl_resource *buffer, int32_t x, int32_t y) {
    (void)client; (void)x; (void)y;
    struct surface *s = wl_resource_get_user_data(resource);
    s->pending_dmabuf = NULL;
    s->pending_shm_resource = NULL;
    s->pending_attached = 1;
    if (!buffer) return;
    if (wl_resource_instance_of(buffer, &wl_buffer_interface, &buffer_impl))
        s->pending_dmabuf = wl_resource_get_user_data(buffer);
    else if (wl_shm_buffer_get(buffer))
        s->pending_shm_resource = buffer;
}
static void surface_damage(struct wl_client *client, struct wl_resource *resource,
                           int32_t x, int32_t y, int32_t width, int32_t height) {
    (void)client;
    struct surface *s = wl_resource_get_user_data(resource);
    damage_add(s, x, y, width, height);
}
static void surface_frame(struct wl_client *client, struct wl_resource *resource, uint32_t id) {
    struct surface *s = wl_resource_get_user_data(resource);
    struct frame_callback *cb = calloc(1, sizeof(*cb));
    if (!cb) { wl_client_post_no_memory(client); return; }
    cb->resource = wl_resource_create(client, &wl_callback_interface, 1, id);
    if (!cb->resource) { free(cb); wl_client_post_no_memory(client); return; }
    wl_list_insert(s->callbacks.prev, &cb->link);
    wl_resource_set_implementation(cb->resource, NULL, cb, callback_destroy_resource);
}
static void surface_region(struct wl_client *client, struct wl_resource *resource, struct wl_resource *region) {
    (void)client; (void)resource; (void)region;
}
static void surface_commit(struct wl_client *client, struct wl_resource *resource) {
    (void)client;
    struct surface *s = wl_resource_get_user_data(resource);
    if (s->pending_attached) {
        s->current_dmabuf = s->pending_dmabuf;
        s->current_shm_resource = s->pending_shm_resource;
        s->pending_dmabuf = NULL;
        s->pending_shm_resource = NULL;
        s->pending_attached = 0;
        if (s->is_toplevel) {
            if (!s->mapped && (s->current_dmabuf || s->current_shm_resource)) {
                s->mapped = 1;
                s->bridge->focus = s;
                focus_enter(s->bridge);
            }
            if (s->current_dmabuf) {
                if (send_dmabuf_frame(s->current_dmabuf) < 0)
                    fprintf(stderr, "[vessel-host] dma-buf forwarding failed: %s\n", strerror(errno));
                wl_buffer_send_release(s->current_dmabuf->resource);
            } else if (s->current_shm_resource) {
                if (send_shm_damage(s, s->current_shm_resource) < 0)
                    fprintf(stderr, "[vessel-host] SHM forwarding failed: %s\n", strerror(errno));
                wl_buffer_send_release(s->current_shm_resource);
            }
        }
    }
    damage_reset(s);
    if (!wl_list_empty(&s->callbacks) || !wl_list_empty(&s->feedbacks)) schedule_frame(s->bridge);
}
static void surface_transform(struct wl_client *c, struct wl_resource *r, int32_t transform) {
    (void)c; (void)r; (void)transform;
}
static void surface_scale(struct wl_client *c, struct wl_resource *r, int32_t scale) {
    (void)c; struct surface *s = wl_resource_get_user_data(r); if (scale > 0) s->buffer_scale = scale;
}
static void surface_damage_buffer(struct wl_client *c, struct wl_resource *r,
                                  int32_t x, int32_t y, int32_t w, int32_t h) {
    surface_damage(c, r, x, y, w, h);
}
static void surface_offset(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y) {
    (void)c; (void)r; (void)x; (void)y;
}
static const struct wl_surface_interface surface_impl = {
    .destroy = surface_destroy, .attach = surface_attach, .damage = surface_damage,
    .frame = surface_frame, .set_opaque_region = surface_region,
    .set_input_region = surface_region, .commit = surface_commit,
    .set_buffer_transform = surface_transform, .set_buffer_scale = surface_scale,
    .damage_buffer = surface_damage_buffer, .offset = surface_offset,
};

static void region_destroy(struct wl_client *c, struct wl_resource *r) { (void)c; wl_resource_destroy(r); }
static void region_add(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y, int32_t w, int32_t h) {
    (void)c; (void)r; (void)x; (void)y; (void)w; (void)h;
}
static void region_subtract(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y, int32_t w, int32_t h) {
    region_add(c, r, x, y, w, h);
}
static const struct wl_region_interface region_impl = {
    .destroy = region_destroy, .add = region_add, .subtract = region_subtract,
};
static void compositor_create_surface(struct wl_client *client, struct wl_resource *resource, uint32_t id) {
    struct bridge *b = wl_resource_get_user_data(resource);
    struct surface *s = calloc(1, sizeof(*s));
    if (!s) { wl_client_post_no_memory(client); return; }
    s->bridge = b; s->buffer_scale = 1;
    wl_list_init(&s->callbacks); wl_list_init(&s->feedbacks);
    wl_list_insert(&b->surfaces, &s->link);
    s->resource = wl_resource_create(client, &wl_surface_interface, wl_resource_get_version(resource), id);
    if (!s->resource) { wl_list_remove(&s->link); free(s); wl_client_post_no_memory(client); return; }
    wl_resource_set_implementation(s->resource, &surface_impl, s, surface_destroy_resource);
}
static void compositor_create_region(struct wl_client *client, struct wl_resource *resource, uint32_t id) {
    (void)resource;
    struct wl_resource *r = wl_resource_create(client, &wl_region_interface, 1, id);
    if (!r) { wl_client_post_no_memory(client); return; }
    wl_resource_set_implementation(r, &region_impl, NULL, NULL);
}
static const struct wl_compositor_interface compositor_impl = {
    .create_surface = compositor_create_surface, .create_region = compositor_create_region,
};
static void bind_compositor(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
    uint32_t v = version > 5 ? 5 : version;
    struct wl_resource *r = wl_resource_create(client, &wl_compositor_interface, v, id);
    if (!r) { wl_client_post_no_memory(client); return; }
    wl_resource_set_implementation(r, &compositor_impl, data, NULL);
}

static void subsurface_destroy(struct wl_client *c, struct wl_resource *r) { (void)c; wl_resource_destroy(r); }
static void subsurface_set_position(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y) {
    (void)c; (void)r; (void)x; (void)y;
}
static void subsurface_place(struct wl_client *c, struct wl_resource *r, struct wl_resource *sibling) {
    (void)c; (void)r; (void)sibling;
}
static void subsurface_sync(struct wl_client *c, struct wl_resource *r) { (void)c; (void)r; }
static const struct wl_subsurface_interface subsurface_impl = {
    .destroy = subsurface_destroy, .set_position = subsurface_set_position,
    .place_above = subsurface_place, .place_below = subsurface_place,
    .set_sync = subsurface_sync, .set_desync = subsurface_sync,
};
static void subcompositor_destroy(struct wl_client *c, struct wl_resource *r) { (void)c; wl_resource_destroy(r); }
static void subcompositor_get_subsurface(struct wl_client *client, struct wl_resource *resource,
                                         uint32_t id, struct wl_resource *surface_resource,
                                         struct wl_resource *parent) {
    (void)resource; (void)parent;
    struct surface *s = wl_resource_get_user_data(surface_resource);
    if (s) s->is_subsurface = 1;
    struct wl_resource *sub = wl_resource_create(client, &wl_subsurface_interface, 1, id);
    if (!sub) { wl_client_post_no_memory(client); return; }
    wl_resource_set_implementation(sub, &subsurface_impl, s, NULL);
}
static const struct wl_subcompositor_interface subcompositor_impl = {
    .destroy = subcompositor_destroy, .get_subsurface = subcompositor_get_subsurface,
};
static void bind_subcompositor(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
    (void)data; uint32_t v = version > 1 ? 1 : version;
    struct wl_resource *r = wl_resource_create(client, &wl_subcompositor_interface, v, id);
    if (!r) { wl_client_post_no_memory(client); return; }
    wl_resource_set_implementation(r, &subcompositor_impl, NULL, NULL);
}

/* Parent clipboard is a compatibility endpoint. The real application clipboard
 * is owned by nested Weston, which provides full selection/DnD semantics to the
 * Linux desktop. */
static void data_source_offer(struct wl_client *c, struct wl_resource *r, const char *mime) { (void)c; (void)r; (void)mime; }
static void data_source_destroy(struct wl_client *c, struct wl_resource *r) { (void)c; wl_resource_destroy(r); }
static void data_source_actions(struct wl_client *c, struct wl_resource *r, uint32_t a) { (void)c; (void)r; (void)a; }
static const struct wl_data_source_interface data_source_impl = {
    .offer = data_source_offer, .destroy = data_source_destroy, .set_actions = data_source_actions,
};
static void data_device_drag(struct wl_client*c,struct wl_resource*r,struct wl_resource*s,
                             struct wl_resource*o,struct wl_resource*i,uint32_t serial) {
    (void)c;(void)r;(void)s;(void)o;(void)i;(void)serial;
}
static void data_device_selection(struct wl_client*c,struct wl_resource*r,struct wl_resource*s,uint32_t serial) {
    (void)c;(void)r;(void)s;(void)serial;
}
static void data_device_release(struct wl_client*c,struct wl_resource*r){(void)c;wl_resource_destroy(r);}
static const struct wl_data_device_interface data_device_impl = {
    .start_drag=data_device_drag,.set_selection=data_device_selection,.release=data_device_release,
};
static void ddm_source(struct wl_client *client, struct wl_resource *resource, uint32_t id) {
    struct wl_resource *r=wl_resource_create(client,&wl_data_source_interface,wl_resource_get_version(resource),id);
    if(!r){wl_client_post_no_memory(client);return;} wl_resource_set_implementation(r,&data_source_impl,NULL,NULL);
}
static void ddm_device(struct wl_client *client, struct wl_resource *resource, uint32_t id, struct wl_resource *seat) {
    (void)seat; struct wl_resource *r=wl_resource_create(client,&wl_data_device_interface,wl_resource_get_version(resource),id);
    if(!r){wl_client_post_no_memory(client);return;} wl_resource_set_implementation(r,&data_device_impl,NULL,NULL);
}
static const struct wl_data_device_manager_interface ddm_impl = {
    .create_data_source=ddm_source,.get_data_device=ddm_device,
};
static void bind_ddm(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
    (void)data; uint32_t v=version>3?3:version;
    struct wl_resource *r=wl_resource_create(client,&wl_data_device_manager_interface,v,id);
    if(!r){wl_client_post_no_memory(client);return;} wl_resource_set_implementation(r,&ddm_impl,NULL,NULL);
}

static void toplevel_destroy(struct wl_client*c,struct wl_resource*r){(void)c;wl_resource_destroy(r);}
static void top_parent(struct wl_client*c,struct wl_resource*r,struct wl_resource*p){(void)c;(void)r;(void)p;}
static void top_string(struct wl_client*c,struct wl_resource*r,const char*s){(void)c;(void)r;(void)s;}
static void top_menu(struct wl_client*c,struct wl_resource*r,struct wl_resource*s,uint32_t serial,int32_t x,int32_t y){(void)c;(void)r;(void)s;(void)serial;(void)x;(void)y;}
static void top_move(struct wl_client*c,struct wl_resource*r,struct wl_resource*s,uint32_t serial){(void)c;(void)r;(void)s;(void)serial;}
static void top_resize(struct wl_client*c,struct wl_resource*r,struct wl_resource*s,uint32_t serial,uint32_t edges){(void)c;(void)r;(void)s;(void)serial;(void)edges;}
static void top_size(struct wl_client*c,struct wl_resource*r,int32_t w,int32_t h){(void)c;(void)r;(void)w;(void)h;}
static void top_simple(struct wl_client*c,struct wl_resource*r){(void)c;(void)r;}
static void top_fullscreen(struct wl_client*c,struct wl_resource*r,struct wl_resource*o){(void)c;(void)r;(void)o;}
static const struct xdg_toplevel_interface toplevel_impl = {
    .destroy=toplevel_destroy,.set_parent=top_parent,.set_title=top_string,.set_app_id=top_string,
    .show_window_menu=top_menu,.move=top_move,.resize=top_resize,.set_max_size=top_size,.set_min_size=top_size,
    .set_maximized=top_simple,.unset_maximized=top_simple,.set_fullscreen=top_fullscreen,
    .unset_fullscreen=top_simple,.set_minimized=top_simple,
};

static void xdg_surface_destroy(struct wl_client*c,struct wl_resource*r){(void)c;wl_resource_destroy(r);}
static void xdg_surface_get_toplevel(struct wl_client *client, struct wl_resource *resource, uint32_t id) {
    struct surface *s=wl_resource_get_user_data(resource); if(s)s->is_toplevel=1;
    struct wl_resource *top=wl_resource_create(client,&xdg_toplevel_interface,1,id);
    if(!top){wl_client_post_no_memory(client);return;}
    wl_resource_set_implementation(top,&toplevel_impl,s,NULL);
    struct wl_array states; wl_array_init(&states);
    uint32_t *st=wl_array_add(&states,2*sizeof(uint32_t));
    if(st){st[0]=XDG_TOPLEVEL_STATE_ACTIVATED;st[1]=XDG_TOPLEVEL_STATE_FULLSCREEN;}
    xdg_toplevel_send_configure(top,s?s->bridge->width:1600,s?s->bridge->height:720,&states);
    wl_array_release(&states);
    if(s)xdg_surface_send_configure(resource,wl_display_next_serial(s->bridge->display));
}
static void xdg_surface_get_popup(struct wl_client*c,struct wl_resource*r,uint32_t id,
                                  struct wl_resource*p,struct wl_resource*pos){
    (void)c;(void)id;(void)p;(void)pos; wl_resource_post_error(r,XDG_WM_BASE_ERROR_ROLE,"parent transport does not host popups");
}
static void xdg_surface_geometry(struct wl_client*c,struct wl_resource*r,int32_t x,int32_t y,int32_t w,int32_t h){(void)c;(void)r;(void)x;(void)y;(void)w;(void)h;}
static void xdg_surface_ack(struct wl_client*c,struct wl_resource*r,uint32_t serial){(void)c;(void)r;(void)serial;}
static const struct xdg_surface_interface xdg_surface_impl={
    .destroy=xdg_surface_destroy,.get_toplevel=xdg_surface_get_toplevel,.get_popup=xdg_surface_get_popup,
    .set_window_geometry=xdg_surface_geometry,.ack_configure=xdg_surface_ack,
};
static void wm_destroy(struct wl_client*c,struct wl_resource*r){(void)c;wl_resource_destroy(r);}
static void wm_positioner(struct wl_client*c,struct wl_resource*r,uint32_t id){(void)c;(void)id;wl_resource_post_error(r,XDG_WM_BASE_ERROR_INVALID_SURFACE_STATE,"positioners unsupported on parent transport");}
static void wm_get_surface(struct wl_client *client, struct wl_resource *resource, uint32_t id, struct wl_resource *surface_resource) {
    struct surface *s=wl_resource_get_user_data(surface_resource);
    if(!s){wl_resource_post_error(resource,XDG_WM_BASE_ERROR_INVALID_SURFACE_STATE,"invalid surface");return;}
    struct wl_resource *xdg=wl_resource_create(client,&xdg_surface_interface,1,id);
    if(!xdg){wl_client_post_no_memory(client);return;} wl_resource_set_implementation(xdg,&xdg_surface_impl,s,NULL);
}
static void wm_pong(struct wl_client*c,struct wl_resource*r,uint32_t serial){(void)c;(void)r;(void)serial;}
static const struct xdg_wm_base_interface wm_impl={.destroy=wm_destroy,.create_positioner=wm_positioner,.get_xdg_surface=wm_get_surface,.pong=wm_pong};
static void bind_wm(struct wl_client *client,void *data,uint32_t version,uint32_t id){
    uint32_t v=version>1?1:version; struct wl_resource*r=wl_resource_create(client,&xdg_wm_base_interface,v,id);
    if(!r){wl_client_post_no_memory(client);return;} wl_resource_set_implementation(r,&wm_impl,data,NULL);
}

static void output_release(struct wl_client*c,struct wl_resource*r){(void)c;wl_resource_destroy(r);}
static const struct wl_output_interface output_impl={.release=output_release};
static void bind_output(struct wl_client *client,void *data,uint32_t version,uint32_t id){
    struct bridge*b=data;uint32_t v=version>3?3:version;struct wl_resource*r=wl_resource_create(client,&wl_output_interface,v,id);
    if(!r){wl_client_post_no_memory(client);return;}wl_resource_set_implementation(r,&output_impl,b,NULL);
    wl_output_send_geometry(r,0,0,0,0,WL_OUTPUT_SUBPIXEL_UNKNOWN,"Vessel","Android Surface",WL_OUTPUT_TRANSFORM_NORMAL);
    wl_output_send_mode(r,WL_OUTPUT_MODE_CURRENT|WL_OUTPUT_MODE_PREFERRED,b->width,b->height,b->refresh_mhz);
    if(v>=2){wl_output_send_scale(r,1);wl_output_send_done(r);}
}

static void presentation_destroy(struct wl_client*c,struct wl_resource*r){(void)c;wl_resource_destroy(r);}
static void presentation_feedback_req(struct wl_client *client, struct wl_resource *resource,
                                      struct wl_resource *surface_resource, uint32_t id) {
    (void)resource;
    struct surface *s=wl_resource_get_user_data(surface_resource);
    if(!s){return;}
    struct presentation_feedback *fb=calloc(1,sizeof(*fb));
    if(!fb){wl_client_post_no_memory(client);return;}
    fb->resource=wl_resource_create(client,&wp_presentation_feedback_interface,1,id);
    if(!fb->resource){free(fb);wl_client_post_no_memory(client);return;}
    wl_list_insert(s->feedbacks.prev,&fb->link);
    wl_resource_set_implementation(fb->resource,&feedback_impl,fb,feedback_destroy_resource);
}
static const struct wp_presentation_interface presentation_impl={
    .destroy=presentation_destroy,.feedback=presentation_feedback_req,
};
static void bind_presentation(struct wl_client *client,void *data,uint32_t version,uint32_t id){
    uint32_t v=version>1?1:version;struct wl_resource*r=wl_resource_create(client,&wp_presentation_interface,v,id);
    if(!r){wl_client_post_no_memory(client);return;}wl_resource_set_implementation(r,&presentation_impl,data,NULL);
    wp_presentation_send_clock_id(r,CLOCK_MONOTONIC);
}

static int init_xkb(struct bridge *b) {
    b->xkb_context=xkb_context_new(XKB_CONTEXT_NO_FLAGS); if(!b->xkb_context)return -1;
    struct xkb_rule_names names={.rules="evdev",.model="pc105",.layout="us"};
    b->xkb_keymap=xkb_keymap_new_from_names(b->xkb_context,&names,XKB_KEYMAP_COMPILE_NO_FLAGS); if(!b->xkb_keymap)return -1;
    b->xkb_state=xkb_state_new(b->xkb_keymap); if(!b->xkb_state)return -1;
    b->keymap_text=xkb_keymap_get_as_string(b->xkb_keymap,XKB_KEYMAP_FORMAT_TEXT_V1); if(!b->keymap_text)return -1;
    b->keymap_size=strlen(b->keymap_text)+1;
    return 0;
}

static void on_signal(int sig){(void)sig;if(g_bridge&&g_bridge->display)wl_display_terminate(g_bridge->display);}

int main(int argc,char **argv){
    struct bridge b={.width=1600,.height=720,.refresh_mhz=60000,.frame_fd=-1,
        .input_listener_fd=-1,.input_client_fd=-1};
    wl_list_init(&b.surfaces);wl_list_init(&b.pointers);wl_list_init(&b.keyboards);wl_list_init(&b.touches);
    b.socket_name="vessel-host-0";b.frame_socket="/tmp/vessel-frame-export.sock";b.input_socket="/tmp/vessel-input.sock";
    b.pointer_x=b.width/2.0;b.pointer_y=b.height/2.0;
    static const struct option opts[]={
        {"socket",required_argument,0,'s'},{"frame-socket",required_argument,0,'f'},
        {"input-socket",required_argument,0,'i'},{"width",required_argument,0,'w'},
        {"height",required_argument,0,'h'},{"refresh",required_argument,0,'r'},{0,0,0,0}};
    int c;while((c=getopt_long(argc,argv,"s:f:i:w:h:r:",opts,NULL))!=-1){
        if(c=='s')b.socket_name=optarg;else if(c=='f')b.frame_socket=optarg;else if(c=='i')b.input_socket=optarg;
        else if(c=='w')b.width=atoi(optarg);else if(c=='h')b.height=atoi(optarg);else if(c=='r')b.refresh_mhz=atoi(optarg)*1000;
    }
    b.pointer_x=b.width/2.0;b.pointer_y=b.height/2.0;
    if(init_xkb(&b)<0){errno=EINVAL;die("xkb keymap");}
    b.display=wl_display_create();if(!b.display)die("wl_display_create");
    b.loop=wl_display_get_event_loop(b.display);g_bridge=&b;
    b.frame_timer=wl_event_loop_add_timer(b.loop,frame_timer_handler,&b);if(!b.frame_timer)die("frame timer");
    if(wl_display_init_shm(b.display)<0)die("wl_display_init_shm");
    if(!wl_global_create(b.display,&wl_compositor_interface,5,&b,bind_compositor))die("wl_compositor global");
    if(!wl_global_create(b.display,&wl_subcompositor_interface,1,&b,bind_subcompositor))die("wl_subcompositor global");
    if(!wl_global_create(b.display,&wl_data_device_manager_interface,3,&b,bind_ddm))die("wl_data_device_manager global");
    if(!wl_global_create(b.display,&wl_seat_interface,7,&b,bind_seat))die("wl_seat global");
    if(!wl_global_create(b.display,&wl_output_interface,3,&b,bind_output))die("wl_output global");
    if(!wl_global_create(b.display,&xdg_wm_base_interface,1,&b,bind_wm))die("xdg_wm_base global");
    if(!wl_global_create(b.display,&zwp_linux_dmabuf_v1_interface,3,&b,bind_dmabuf))die("linux-dmabuf global");
    if(!wl_global_create(b.display,&wp_presentation_interface,1,&b,bind_presentation))die("presentation global");
    if(wl_display_add_socket(b.display,b.socket_name)<0)die("wl_display_add_socket");
    setup_input_socket(&b);
    signal(SIGINT,on_signal);signal(SIGTERM,on_signal);
    fprintf(stderr,"[vessel-host] READY socket=%s size=%dx%d refresh=%d shm-damage=1 dmabuf-modifiers=1 input=direct presentation=1 frame=%s\n",
            b.socket_name,b.width,b.height,b.refresh_mhz/1000,b.frame_socket);
    wl_display_run(b.display);
    if(b.input_client_source)wl_event_source_remove(b.input_client_source);
    if(b.input_listener_source)wl_event_source_remove(b.input_listener_source);
    if(b.input_client_fd>=0)close(b.input_client_fd);if(b.input_listener_fd>=0)close(b.input_listener_fd);unlink(b.input_socket);
    disconnect_frame(&b);wl_display_destroy_clients(b.display);wl_display_destroy(b.display);
    free(b.keymap_text);if(b.xkb_state)xkb_state_unref(b.xkb_state);if(b.xkb_keymap)xkb_keymap_unref(b.xkb_keymap);if(b.xkb_context)xkb_context_unref(b.xkb_context);
    return 0;
}
