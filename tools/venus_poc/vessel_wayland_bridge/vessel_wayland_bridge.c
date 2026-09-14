#define _GNU_SOURCE
#include <wayland-server-core.h>
#include <wayland-server-protocol.h>
#include "xdg-shell-server-protocol.h"
#include "linux-dmabuf-unstable-v1-server-protocol.h"

#include <errno.h>
#include <fcntl.h>
#include <getopt.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define VESSEL_MAGIC 0x31574656u
#define VESSEL_IMPORT 1u
#define VESSEL_FRAME 2u

static uint32_t fourcc(char a, char b, char c, char d) {
    return (uint32_t)a | ((uint32_t)b << 8) | ((uint32_t)c << 16) | ((uint32_t)d << 24);
}

#pragma pack(push, 1)
struct vessel_frame_msg {
    uint32_t magic, type, width, height, fourcc, stride, offset, reserved;
    uint64_t modifier, serial;
};
#pragma pack(pop)

struct bridge;
struct surface;

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

struct surface {
    struct bridge *bridge;
    struct wl_resource *resource;
    struct vessel_buffer *pending;
    struct vessel_buffer *current;
    struct wl_list callbacks;
    int32_t buffer_scale;
};

struct bridge {
    struct wl_display *display;
    struct wl_event_loop *loop;
    const char *socket_name;
    const char *frame_socket;
    int width, height, refresh_mhz;
    int frame_fd;
    uint64_t next_buffer_serial;
};

static struct bridge *g_bridge;

static void die(const char *what) {
    fprintf(stderr, "[vessel-wayland-bridge] %s: %s\n", what, strerror(errno));
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
        close(fd); errno = ENAMETOOLONG; return -1;
    }
    strncpy(addr.sun_path, b->frame_socket, sizeof(addr.sun_path) - 1);
    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        close(fd); return -1;
    }
    b->frame_fd = fd;
    fprintf(stderr, "[vessel-wayland-bridge] connected native frame transport %s\n", b->frame_socket);
    return 0;
}

static int send_import(struct vessel_buffer *buf) {
    struct bridge *b = buf->bridge;
    if (connect_frame(b) < 0) return -1;
    struct vessel_frame_msg msg = {
        .magic = VESSEL_MAGIC, .type = VESSEL_IMPORT,
        .width = buf->width, .height = buf->height, .fourcc = buf->format,
        .stride = buf->stride, .offset = buf->offset,
        .modifier = buf->modifier, .serial = buf->serial,
    };
    struct iovec iov = { .iov_base = &msg, .iov_len = sizeof(msg) };
    char control[CMSG_SPACE(sizeof(int))] = {0};
    struct msghdr hdr = {0};
    hdr.msg_iov = &iov; hdr.msg_iovlen = 1;
    hdr.msg_control = control; hdr.msg_controllen = sizeof(control);
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&hdr);
    cmsg->cmsg_level = SOL_SOCKET; cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(cmsg), &buf->fd, sizeof(int));
    ssize_t n = sendmsg(b->frame_fd, &hdr, MSG_NOSIGNAL);
    if (n != (ssize_t)sizeof(msg)) {
        disconnect_frame(b); return -1;
    }
    buf->imported = 1;
    fprintf(stderr, "[vessel-wayland-bridge] imported wl_buffer id=%llu %ux%u fourcc=%08x\n",
            (unsigned long long)buf->serial, buf->width, buf->height, buf->format);
    return 0;
}

static int send_frame(struct vessel_buffer *buf) {
    struct bridge *b = buf->bridge;
    if (!buf->imported && send_import(buf) < 0) return -1;
    struct vessel_frame_msg msg = {
        .magic = VESSEL_MAGIC, .type = VESSEL_FRAME,
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

static const struct wl_buffer_interface buffer_impl = {
    .destroy = buffer_destroy,
};

static struct wl_resource *create_dmabuf_buffer(struct dmabuf_params *p, struct wl_client *client,
                                                 uint32_t id, int32_t width, int32_t height,
                                                 uint32_t format) {
    if (!p->have_plane0 || p->fd < 0 || width <= 0 || height <= 0) return NULL;
    struct vessel_buffer *buf = calloc(1, sizeof(*buf));
    if (!buf) return NULL;
    buf->bridge = p->bridge;
    buf->fd = p->fd; p->fd = -1;
    buf->width = (uint32_t)width; buf->height = (uint32_t)height;
    buf->format = format; buf->stride = p->stride; buf->offset = p->offset;
    buf->modifier = p->modifier;
    buf->serial = ++p->bridge->next_buffer_serial;
    struct wl_resource *res = wl_resource_create(client, &wl_buffer_interface, 1, id);
    if (!res) { close(buf->fd); free(buf); return NULL; }
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
    (void)client; wl_resource_destroy(resource);
}

static void params_add(struct wl_client *client, struct wl_resource *resource, int32_t fd,
                       uint32_t plane_idx, uint32_t offset, uint32_t stride,
                       uint32_t modifier_hi, uint32_t modifier_lo) {
    (void)client;
    struct dmabuf_params *p = wl_resource_get_user_data(resource);
    if (!p || plane_idx != 0 || p->have_plane0) { close(fd); return; }
    p->fd = fd; p->offset = offset; p->stride = stride;
    p->modifier = ((uint64_t)modifier_hi << 32) | modifier_lo;
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

static void params_create_immed(struct wl_client *client, struct wl_resource *resource, uint32_t buffer_id,
                                int32_t width, int32_t height, uint32_t format, uint32_t flags) {
    (void)flags;
    struct dmabuf_params *p = wl_resource_get_user_data(resource);
    if (!create_dmabuf_buffer(p, client, buffer_id, width, height, format)) {
        wl_resource_post_error(resource, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INVALID_WL_BUFFER,
                               "Vessel requires a valid single-plane dma-buf");
    }
}

static const struct zwp_linux_buffer_params_v1_interface params_impl = {
    .destroy = params_destroy, .add = params_add, .create = params_create, .create_immed = params_create_immed,
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
    .destroy = dmabuf_destroy,
    .create_params = dmabuf_create_params,
    .get_default_feedback = NULL,
    .get_surface_feedback = NULL,
};

static void bind_dmabuf(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
    struct bridge *b = data;
    uint32_t v = version > 3 ? 3 : version;
    struct wl_resource *res = wl_resource_create(client, &zwp_linux_dmabuf_v1_interface, v, id);
    if (!res) { wl_client_post_no_memory(client); return; }
    wl_resource_set_implementation(res, &dmabuf_impl, b, NULL);
    zwp_linux_dmabuf_v1_send_format(res, fourcc('X','R','2','4'));
    zwp_linux_dmabuf_v1_send_format(res, fourcc('A','R','2','4'));
    zwp_linux_dmabuf_v1_send_format(res, fourcc('X','B','2','4'));
    zwp_linux_dmabuf_v1_send_format(res, fourcc('A','B','2','4'));
}

static void callback_destroy_resource(struct wl_resource *resource) {
    struct frame_callback *cb = wl_resource_get_user_data(resource);
    if (!cb) return;
    wl_list_remove(&cb->link);
    free(cb);
}

static void surface_destroy_resource(struct wl_resource *resource) {
    struct surface *s = wl_resource_get_user_data(resource);
    if (!s) return;
    struct frame_callback *cb, *tmp;
    wl_list_for_each_safe(cb, tmp, &s->callbacks, link) wl_resource_destroy(cb->resource);
    free(s);
}

static void surface_destroy(struct wl_client *client, struct wl_resource *resource) {
    (void)client; wl_resource_destroy(resource);
}

static void surface_attach(struct wl_client *client, struct wl_resource *resource,
                           struct wl_resource *buffer, int32_t x, int32_t y) {
    (void)client; (void)x; (void)y;
    struct surface *s = wl_resource_get_user_data(resource);
    s->pending = buffer ? wl_resource_get_user_data(buffer) : NULL;
}

static void surface_damage(struct wl_client *client, struct wl_resource *resource,
                           int32_t x, int32_t y, int32_t width, int32_t height) {
    (void)client; (void)resource; (void)x; (void)y; (void)width; (void)height;
}

static void surface_frame(struct wl_client *client, struct wl_resource *resource, uint32_t callback_id) {
    struct surface *s = wl_resource_get_user_data(resource);
    struct frame_callback *cb = calloc(1, sizeof(*cb));
    if (!cb) { wl_client_post_no_memory(client); return; }
    cb->resource = wl_resource_create(client, &wl_callback_interface, 1, callback_id);
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
    if (s->pending) s->current = s->pending;
    s->pending = NULL;
    if (s->current && send_frame(s->current) < 0) {
        fprintf(stderr, "[vessel-wayland-bridge] frame forwarding failed: %s\n", strerror(errno));
    }
    uint32_t now = (uint32_t)(clock() * 1000 / CLOCKS_PER_SEC);
    struct frame_callback *cb, *tmp;
    wl_list_for_each_safe(cb, tmp, &s->callbacks, link) {
        wl_callback_send_done(cb->resource, now);
        wl_resource_destroy(cb->resource);
    }
    if (s->current && s->current->resource) wl_buffer_send_release(s->current->resource);
}

static void surface_transform(struct wl_client *client, struct wl_resource *resource, int32_t transform) {
    (void)client; (void)resource; (void)transform;
}
static void surface_scale(struct wl_client *client, struct wl_resource *resource, int32_t scale) {
    (void)client; struct surface *s = wl_resource_get_user_data(resource); if (scale > 0) s->buffer_scale = scale;
}
static void surface_damage_buffer(struct wl_client *client, struct wl_resource *resource,
                                  int32_t x, int32_t y, int32_t w, int32_t h) {
    surface_damage(client, resource, x, y, w, h);
}
static void surface_offset(struct wl_client *client, struct wl_resource *resource, int32_t x, int32_t y) {
    (void)client; (void)resource; (void)x; (void)y;
}

static const struct wl_surface_interface surface_impl = {
    .destroy = surface_destroy, .attach = surface_attach, .damage = surface_damage,
    .frame = surface_frame, .set_opaque_region = surface_region, .set_input_region = surface_region,
    .commit = surface_commit, .set_buffer_transform = surface_transform,
    .set_buffer_scale = surface_scale, .damage_buffer = surface_damage_buffer, .offset = surface_offset,
};

static void region_destroy(struct wl_client *client, struct wl_resource *resource) { (void)client; wl_resource_destroy(resource); }
static void region_add(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y, int32_t w, int32_t h) { (void)c;(void)r;(void)x;(void)y;(void)w;(void)h; }
static void region_subtract(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y, int32_t w, int32_t h) { region_add(c,r,x,y,w,h); }
static const struct wl_region_interface region_impl = { .destroy=region_destroy, .add=region_add, .subtract=region_subtract };

static void compositor_create_surface(struct wl_client *client, struct wl_resource *resource, uint32_t id) {
    struct bridge *b = wl_resource_get_user_data(resource);
    struct surface *s = calloc(1, sizeof(*s));
    if (!s) { wl_client_post_no_memory(client); return; }
    s->bridge = b; s->buffer_scale = 1; wl_list_init(&s->callbacks);
    s->resource = wl_resource_create(client, &wl_surface_interface, wl_resource_get_version(resource), id);
    if (!s->resource) { free(s); wl_client_post_no_memory(client); return; }
    wl_resource_set_implementation(s->resource, &surface_impl, s, surface_destroy_resource);
}
static void compositor_create_region(struct wl_client *client, struct wl_resource *resource, uint32_t id) {
    (void)resource;
    struct wl_resource *r = wl_resource_create(client, &wl_region_interface, 1, id);
    if (!r) { wl_client_post_no_memory(client); return; }
    wl_resource_set_implementation(r, &region_impl, NULL, NULL);
}
static const struct wl_compositor_interface compositor_impl = { .create_surface=compositor_create_surface, .create_region=compositor_create_region };
static void bind_compositor(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
    uint32_t v = version > 4 ? 4 : version;
    struct wl_resource *r = wl_resource_create(client, &wl_compositor_interface, v, id);
    if (!r) { wl_client_post_no_memory(client); return; }
    wl_resource_set_implementation(r, &compositor_impl, data, NULL);
}

static void xdg_toplevel_destroy(struct wl_client *c, struct wl_resource *r) { (void)c; wl_resource_destroy(r); }
static void noop_parent(struct wl_client*c,struct wl_resource*r,struct wl_resource*p){(void)c;(void)r;(void)p;}
static void noop_string(struct wl_client*c,struct wl_resource*r,const char*s){(void)c;(void)r;(void)s;}
static void noop_move(struct wl_client*c,struct wl_resource*r,struct wl_resource*s,uint32_t serial){(void)c;(void)r;(void)s;(void)serial;}
static void noop_resize(struct wl_client*c,struct wl_resource*r,struct wl_resource*s,uint32_t serial,uint32_t edges){(void)c;(void)r;(void)s;(void)serial;(void)edges;}
static void noop_size(struct wl_client*c,struct wl_resource*r,int32_t w,int32_t h){(void)c;(void)r;(void)w;(void)h;}
static void noop_simple(struct wl_client*c,struct wl_resource*r){(void)c;(void)r;}
static void noop_fullscreen(struct wl_client*c,struct wl_resource*r,struct wl_resource*o){(void)c;(void)r;(void)o;}
static void noop_menu(struct wl_client*c,struct wl_resource*r,struct wl_resource*s,uint32_t serial,int32_t x,int32_t y){(void)c;(void)r;(void)s;(void)serial;(void)x;(void)y;}
static const struct xdg_toplevel_interface toplevel_impl = {
    .destroy=xdg_toplevel_destroy,.set_parent=noop_parent,.set_title=noop_string,.set_app_id=noop_string,
    .show_window_menu=noop_menu,.move=noop_move,.resize=noop_resize,.set_max_size=noop_size,.set_min_size=noop_size,
    .set_maximized=noop_simple,.unset_maximized=noop_simple,.set_fullscreen=noop_fullscreen,
    .unset_fullscreen=noop_simple,.set_minimized=noop_simple,
};

static void xdg_surface_destroy(struct wl_client*c,struct wl_resource*r){(void)c;wl_resource_destroy(r);}
static void xdg_surface_get_toplevel(struct wl_client *client, struct wl_resource *resource, uint32_t id) {
    struct surface *s = wl_resource_get_user_data(resource);
    struct wl_resource *top = wl_resource_create(client, &xdg_toplevel_interface, 1, id);
    if (!top) { wl_client_post_no_memory(client); return; }
    wl_resource_set_implementation(top, &toplevel_impl, s, NULL);
    struct wl_array states; wl_array_init(&states);
    xdg_toplevel_send_configure(top, s->bridge->width, s->bridge->height, &states);
    wl_array_release(&states);
    xdg_surface_send_configure(resource, wl_display_next_serial(s->bridge->display));
}
static void xdg_surface_get_popup(struct wl_client*c,struct wl_resource*r,uint32_t id,struct wl_resource*p,struct wl_resource*pos){(void)id;(void)p;(void)pos;wl_resource_post_error(r,XDG_WM_BASE_ERROR_ROLE,"popups unsupported by Vessel output bridge");(void)c;}
static void xdg_surface_geometry(struct wl_client*c,struct wl_resource*r,int32_t x,int32_t y,int32_t w,int32_t h){(void)c;(void)r;(void)x;(void)y;(void)w;(void)h;}
static void xdg_surface_ack(struct wl_client*c,struct wl_resource*r,uint32_t serial){(void)c;(void)r;(void)serial;}
static const struct xdg_surface_interface xdg_surface_impl = {
    .destroy=xdg_surface_destroy,.get_toplevel=xdg_surface_get_toplevel,.get_popup=xdg_surface_get_popup,
    .set_window_geometry=xdg_surface_geometry,.ack_configure=xdg_surface_ack,
};

static void wm_destroy(struct wl_client*c,struct wl_resource*r){(void)c;wl_resource_destroy(r);}
static void wm_create_positioner(struct wl_client*c,struct wl_resource*r,uint32_t id){(void)id;wl_resource_post_error(r,XDG_WM_BASE_ERROR_INVALID_SURFACE_STATE,"positioners unsupported");(void)c;}
static void wm_get_xdg_surface(struct wl_client *client, struct wl_resource *resource, uint32_t id, struct wl_resource *surface_resource) {
    struct surface *s = wl_resource_get_user_data(surface_resource);
    if (!s) { wl_resource_post_error(resource, XDG_WM_BASE_ERROR_INVALID_SURFACE_STATE, "invalid surface"); return; }
    struct wl_resource *xdg = wl_resource_create(client, &xdg_surface_interface, 1, id);
    if (!xdg) { wl_client_post_no_memory(client); return; }
    wl_resource_set_implementation(xdg, &xdg_surface_impl, s, NULL);
}
static void wm_pong(struct wl_client*c,struct wl_resource*r,uint32_t serial){(void)c;(void)r;(void)serial;}
static const struct xdg_wm_base_interface wm_impl = { .destroy=wm_destroy,.create_positioner=wm_create_positioner,.get_xdg_surface=wm_get_xdg_surface,.pong=wm_pong };
static void bind_wm(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
    struct wl_resource *r = wl_resource_create(client, &xdg_wm_base_interface, version > 1 ? 1 : version, id);
    if (!r) { wl_client_post_no_memory(client); return; }
    wl_resource_set_implementation(r, &wm_impl, data, NULL);
}

static void output_release(struct wl_client*c,struct wl_resource*r){(void)c;wl_resource_destroy(r);}
static const struct wl_output_interface output_impl = { .release=output_release };
static void bind_output(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
    struct bridge *b = data; uint32_t v = version > 3 ? 3 : version;
    struct wl_resource *r = wl_resource_create(client, &wl_output_interface, v, id);
    if (!r) { wl_client_post_no_memory(client); return; }
    wl_resource_set_implementation(r, &output_impl, b, NULL);
    wl_output_send_geometry(r,0,0,b->width,b->height,WL_OUTPUT_SUBPIXEL_UNKNOWN,"Vessel","Android Surface",WL_OUTPUT_TRANSFORM_NORMAL);
    wl_output_send_mode(r,WL_OUTPUT_MODE_CURRENT|WL_OUTPUT_MODE_PREFERRED,b->width,b->height,b->refresh_mhz);
    if (v >= 2) { wl_output_send_scale(r,1); wl_output_send_done(r); }
}

static void on_signal(int sig) { (void)sig; if (g_bridge && g_bridge->display) wl_display_terminate(g_bridge->display); }

int main(int argc, char **argv) {
    struct bridge b = { .width=1600,.height=720,.refresh_mhz=60000,.frame_fd=-1,.next_buffer_serial=0 };
    b.socket_name="vessel-host-0"; b.frame_socket="/tmp/vessel-frame-export.sock";
    static const struct option opts[] = {
        {"socket",required_argument,0,'s'},{"frame-socket",required_argument,0,'f'},
        {"width",required_argument,0,'w'},{"height",required_argument,0,'h'},
        {"refresh",required_argument,0,'r'},{0,0,0,0}
    };
    int c; while ((c=getopt_long(argc,argv,"s:f:w:h:r:",opts,NULL))!=-1) {
        if(c=='s')b.socket_name=optarg; else if(c=='f')b.frame_socket=optarg;
        else if(c=='w')b.width=atoi(optarg); else if(c=='h')b.height=atoi(optarg); else if(c=='r')b.refresh_mhz=atoi(optarg)*1000;
    }
    b.display=wl_display_create(); if(!b.display)die("wl_display_create"); b.loop=wl_display_get_event_loop(b.display); g_bridge=&b;
    if(wl_display_init_shm(b.display)<0)die("wl_display_init_shm");
    if(!wl_global_create(b.display,&wl_compositor_interface,4,&b,bind_compositor))die("wl_compositor global");
    if(!wl_global_create(b.display,&wl_output_interface,3,&b,bind_output))die("wl_output global");
    if(!wl_global_create(b.display,&xdg_wm_base_interface,1,&b,bind_wm))die("xdg_wm_base global");
    if(!wl_global_create(b.display,&zwp_linux_dmabuf_v1_interface,3,&b,bind_dmabuf))die("linux-dmabuf global");
    if(wl_display_add_socket(b.display,b.socket_name)<0)die("wl_display_add_socket");
    signal(SIGINT,on_signal); signal(SIGTERM,on_signal);
    fprintf(stderr,"[vessel-wayland-bridge] READY socket=%s size=%dx%d dmabuf=1 frame=%s\n",b.socket_name,b.width,b.height,b.frame_socket);
    wl_display_run(b.display);
    disconnect_frame(&b); wl_display_destroy_clients(b.display); wl_display_destroy(b.display); return 0;
}
