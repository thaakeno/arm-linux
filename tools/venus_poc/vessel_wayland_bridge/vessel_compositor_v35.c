#define _GNU_SOURCE
#include <wayland-server-core.h>
#include <wayland-server-protocol.h>
#include <xkbcommon/xkbcommon.h>
#include "xdg-shell-server-protocol.h"
#include "linux-dmabuf-unstable-v1-server-protocol.h"
#include "text-input-unstable-v3-server-protocol.h"
#include "presentation-time-server-protocol.h"
#include "xdg-decoration-unstable-v1-server-protocol.h"

#include <errno.h>
#include <fcntl.h>
#include <getopt.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>
#include <arpa/inet.h>

#define VESSEL_MAGIC 0x31574656u
#define VESSEL_IMPORT 1u
#define VESSEL_FRAME 2u
#define VESSEL_SHM_DAMAGE 4u
#define DRM_FORMAT_MOD_LINEAR 0ULL
#define DRM_FORMAT_MOD_INVALID 0x00ffffffffffffffULL
#define MAX_INPUT_LINE 16384

struct bridge;
struct surface;

static uint32_t fourcc(char a, char b, char c, char d) {
    return (uint32_t)a | ((uint32_t)b << 8) | ((uint32_t)c << 16) | ((uint32_t)d << 24);
}

#pragma pack(push, 1)
struct vessel_frame_msg {
    uint32_t magic, type, width, height, fourcc, stride, offset, reserved;
    uint64_t modifier, serial;
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

struct present_feedback {
    struct wl_list link;
    struct wl_resource *resource;
};

struct surface {
    struct wl_list link;
    struct bridge *bridge;
    struct wl_resource *resource;
    struct wl_resource *xdg_surface;
    struct wl_resource *xdg_toplevel;
    struct wl_resource *pending_resource;
    struct vessel_buffer *pending_dmabuf;
    struct vessel_buffer *current_dmabuf;
    struct wl_list callbacks;
    struct wl_list presentation;
    struct surface *parent;
    int32_t sub_x, sub_y;
    int32_t buffer_scale;
    int32_t damage_x1, damage_y1, damage_x2, damage_y2;
    int pending_attached;
    int damage_valid;
    int mapped;
    int is_toplevel;
    int is_popup;
    int is_subsurface;
    char *title;
    char *app_id;
};

struct resource_node {
    struct wl_list link;
    struct wl_resource *resource;
};

struct mime_node {
    struct wl_list link;
    char *mime;
};

struct data_source {
    struct bridge *bridge;
    struct wl_resource *resource;
    struct wl_list mimes;
    int refs;
};

struct data_offer {
    struct data_source *source;
};

struct text_input {
    struct wl_list link;
    struct bridge *bridge;
    struct wl_resource *resource;
    int enabled;
};

struct positioner {
    int32_t width, height;
    int32_t anchor_x, anchor_y, anchor_w, anchor_h;
    int32_t offset_x, offset_y;
};

struct bridge {
    struct wl_display *display;
    struct wl_event_loop *loop;
    struct wl_event_source *frame_timer;
    struct wl_event_source *input_timer;
    struct wl_event_source *input_source;
    struct wl_list surfaces;
    struct wl_list pointers;
    struct wl_list keyboards;
    struct wl_list touches;
    struct wl_list data_devices;
    struct wl_list text_inputs;
    struct surface *active;
    struct data_source *selection;
    const char *socket_name;
    const char *frame_socket;
    const char *input_host;
    int input_port;
    int width, height, refresh_mhz;
    int frame_fd;
    int frame_timer_armed;
    int input_fd;
    char input_buf[MAX_INPUT_LINE];
    size_t input_len;
    double pointer_x, pointer_y;
    int touch_down;
    uint64_t next_buffer_serial;
    uint64_t frame_seq;
    uint64_t present_seq;
    uint32_t text_serial;
    struct xkb_context *xkb_context;
    struct xkb_keymap *xkb_keymap;
    struct xkb_state *xkb_state;
    int keymap_fd;
    size_t keymap_size;
};

static struct bridge *g_bridge;

static uint32_t monotonic_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint32_t)((uint64_t)ts.tv_sec * 1000u + (uint64_t)ts.tv_nsec / 1000000u);
}

static void monotonic_ts(struct timespec *ts) {
    clock_gettime(CLOCK_MONOTONIC, ts);
}

static void die(const char *what) {
    fprintf(stderr, "[vessel-compositor] %s: %s\n", what, strerror(errno));
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
    fprintf(stderr, "[vessel-compositor] frame transport connected %s\n", b->frame_socket);
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
    if (n != (ssize_t)sizeof(msg)) { disconnect_frame(b); return -1; }
    buf->imported = 1;
    fprintf(stderr, "[vessel-compositor] dmabuf import id=%llu %ux%u fmt=%08x mod=%llx\n",
            (unsigned long long)buf->serial, buf->width, buf->height, buf->format,
            (unsigned long long)buf->modifier);
    return 0;
}

static int send_dmabuf_frame(struct vessel_buffer *buf) {
    struct bridge *b = buf->bridge;
    if (!buf->imported && send_import(buf) < 0) return -1;
    struct vessel_frame_msg msg = {
        .magic = VESSEL_MAGIC, .type = VESSEL_FRAME,
        .width = buf->width, .height = buf->height, .fourcc = buf->format,
        .stride = buf->stride, .offset = buf->offset,
        .modifier = buf->modifier, .serial = buf->serial,
    };
    if (send_all(b->frame_fd, &msg, sizeof(msg)) < 0) {
        disconnect_frame(b); buf->imported = 0; return -1;
    }
    return 0;
}

static void surface_global_offset(struct surface *s, int32_t *x, int32_t *y) {
    *x = 0; *y = 0;
    for (struct surface *p = s; p; p = p->parent) {
        *x += p->sub_x; *y += p->sub_y;
    }
}

static void damage_add(struct surface *s, int32_t x, int32_t y, int32_t w, int32_t h) {
    if (w <= 0 || h <= 0) return;
    int64_t x2 = (int64_t)x + w, y2 = (int64_t)y + h;
    if (!s->damage_valid) {
        s->damage_x1 = x; s->damage_y1 = y; s->damage_x2 = (int32_t)x2; s->damage_y2 = (int32_t)y2;
        s->damage_valid = 1;
    } else {
        if (x < s->damage_x1) s->damage_x1 = x;
        if (y < s->damage_y1) s->damage_y1 = y;
        if (x2 > s->damage_x2) s->damage_x2 = (int32_t)x2;
        if (y2 > s->damage_y2) s->damage_y2 = (int32_t)y2;
    }
}

static int send_shm_damage(struct surface *s, struct wl_resource *buffer_resource) {
    struct bridge *b = s->bridge;
    struct wl_shm_buffer *shm = wl_shm_buffer_get(buffer_resource);
    if (!shm) return -1;
    int32_t bw = wl_shm_buffer_get_width(shm);
    int32_t bh = wl_shm_buffer_get_height(shm);
    int32_t src_stride = wl_shm_buffer_get_stride(shm);
    uint32_t sfmt = wl_shm_buffer_get_format(shm);
    uint32_t fmt;
    if (sfmt == WL_SHM_FORMAT_ARGB8888) fmt = fourcc('A','R','2','4');
    else if (sfmt == WL_SHM_FORMAT_XRGB8888) fmt = fourcc('X','R','2','4');
    else return -1;
    if (bw <= 0 || bh <= 0 || src_stride < bw * 4) return -1;

    int32_t x1 = s->damage_valid ? s->damage_x1 : 0;
    int32_t y1 = s->damage_valid ? s->damage_y1 : 0;
    int32_t x2 = s->damage_valid ? s->damage_x2 : bw;
    int32_t y2 = s->damage_valid ? s->damage_y2 : bh;
    if (x1 < 0) x1 = 0; if (y1 < 0) y1 = 0;
    if (x2 > bw) x2 = bw; if (y2 > bh) y2 = bh;
    if (x2 <= x1 || y2 <= y1) return 0;

    int32_t gx, gy; surface_global_offset(s, &gx, &gy);
    int32_t dx = gx + x1, dy = gy + y1;
    if (dx < 0 || dy < 0 || dx >= b->width || dy >= b->height) return 0;
    int32_t dw = x2 - x1, dh = y2 - y1;
    if (dx + dw > b->width) dw = b->width - dx;
    if (dy + dh > b->height) dh = b->height - dy;
    if (dw <= 0 || dh <= 0) return 0;

    if (connect_frame(b) < 0) return -1;
    uint32_t tight_stride = (uint32_t)dw * 4u;
    struct vessel_frame_msg msg = {
        .magic = VESSEL_MAGIC, .type = VESSEL_SHM_DAMAGE,
        .width = (uint32_t)b->width, .height = (uint32_t)b->height,
        .fourcc = fmt, .stride = tight_stride,
        .offset = (uint32_t)dx, .reserved = (uint32_t)dy,
        .modifier = ((uint64_t)(uint32_t)dh << 32u) | (uint32_t)dw,
        .serial = ++b->next_buffer_serial,
    };
    if (send_all(b->frame_fd, &msg, sizeof(msg)) < 0) { disconnect_frame(b); return -1; }
    wl_shm_buffer_begin_access(shm);
    const uint8_t *src = wl_shm_buffer_get_data(shm);
    int rc = 0;
    for (int32_t row = 0; row < dh; ++row) {
        const uint8_t *p = src + (size_t)(y1 + row) * (size_t)src_stride + (size_t)x1 * 4u;
        if (send_all(b->frame_fd, p, tight_stride) < 0) { rc = -1; break; }
    }
    wl_shm_buffer_end_access(shm);
    if (rc < 0) disconnect_frame(b);
    return rc;
}

static void schedule_frame_callbacks(struct bridge *b);
static void activate_surface(struct bridge *b, struct surface *s);

static void presentation_feedback_destroy_resource(struct wl_resource *resource) {
    struct present_feedback *pf = wl_resource_get_user_data(resource);
    if (!pf) return;
    wl_list_remove(&pf->link); free(pf);
}

static void presentation_feedback_destroy(struct wl_client *c, struct wl_resource *r) {
    (void)c; wl_resource_destroy(r);
}

static const struct wp_presentation_feedback_interface presentation_feedback_impl = {
    .destroy = presentation_feedback_destroy,
};

static void finish_presentation(struct surface *s, int presented) {
    struct present_feedback *pf, *tmp;
    struct timespec ts; monotonic_ts(&ts);
    uint64_t sec = (uint64_t)ts.tv_sec;
    uint32_t refresh_ns = s->bridge->refresh_mhz > 0 ? (uint32_t)(1000000000000ULL / (uint64_t)s->bridge->refresh_mhz) : 16666667u;
    uint64_t seq = ++s->bridge->present_seq;
    wl_list_for_each_safe(pf, tmp, &s->presentation, link) {
        if (presented) {
            wp_presentation_feedback_send_presented(pf->resource,
                (uint32_t)(sec >> 32), (uint32_t)sec, (uint32_t)ts.tv_nsec,
                refresh_ns, (uint32_t)(seq >> 32), (uint32_t)seq, 0);
        } else {
            wp_presentation_feedback_send_discarded(pf->resource);
        }
        wl_resource_destroy(pf->resource);
    }
}

static int frame_timer_handler(void *data) {
    struct bridge *b = data;
    b->frame_timer_armed = 0; b->frame_seq++;
    uint32_t now = monotonic_ms();
    struct surface *s;
    wl_list_for_each(s, &b->surfaces, link) {
        struct frame_callback *cb, *tmp;
        if (s == b->active || (s->parent && s->parent == b->active)) {
            wl_list_for_each_safe(cb, tmp, &s->callbacks, link) {
                wl_callback_send_done(cb->resource, now);
                wl_resource_destroy(cb->resource);
            }
            finish_presentation(s, 1);
        }
    }
    return 0;
}

static void schedule_frame_callbacks(struct bridge *b) {
    if (b->frame_timer_armed || !b->frame_timer) return;
    int delay_ms = b->refresh_mhz > 0 ? (1000000 + b->refresh_mhz - 1) / b->refresh_mhz : 17;
    if (delay_ms < 1) delay_ms = 1;
    wl_event_source_timer_update(b->frame_timer, delay_ms);
    b->frame_timer_armed = 1;
}

/* dma-buf */
static void buffer_destroy_resource(struct wl_resource *resource) {
    struct vessel_buffer *buf = wl_resource_get_user_data(resource);
    if (!buf) return;
    if (buf->fd >= 0) close(buf->fd);
    free(buf);
}
static void buffer_destroy(struct wl_client *c, struct wl_resource *r) { (void)c; wl_resource_destroy(r); }
static const struct wl_buffer_interface buffer_impl = { .destroy = buffer_destroy };

static struct wl_resource *create_dmabuf_buffer(struct dmabuf_params *p, struct wl_client *client,
        uint32_t id, int32_t width, int32_t height, uint32_t format) {
    if (!p->have_plane0 || p->fd < 0 || width <= 0 || height <= 0) return NULL;
    struct vessel_buffer *buf = calloc(1, sizeof(*buf)); if (!buf) return NULL;
    buf->bridge=p->bridge; buf->fd=p->fd; p->fd=-1; buf->width=(uint32_t)width; buf->height=(uint32_t)height;
    buf->format=format; buf->stride=p->stride; buf->offset=p->offset; buf->modifier=p->modifier;
    buf->serial=++p->bridge->next_buffer_serial;
    struct wl_resource *res=wl_resource_create(client,&wl_buffer_interface,1,id);
    if(!res){close(buf->fd);free(buf);return NULL;} buf->resource=res;
    wl_resource_set_implementation(res,&buffer_impl,buf,buffer_destroy_resource); return res;
}
static void params_destroy_resource(struct wl_resource *r){struct dmabuf_params*p=wl_resource_get_user_data(r);if(!p)return;if(p->fd>=0)close(p->fd);free(p);}
static void params_destroy(struct wl_client*c,struct wl_resource*r){(void)c;wl_resource_destroy(r);}
static void params_add(struct wl_client*c,struct wl_resource*r,int32_t fd,uint32_t plane,uint32_t off,uint32_t stride,uint32_t hi,uint32_t lo){
    (void)c;struct dmabuf_params*p=wl_resource_get_user_data(r);if(!p||plane!=0||p->have_plane0){close(fd);return;}p->fd=fd;p->offset=off;p->stride=stride;p->modifier=((uint64_t)hi<<32)|lo;p->have_plane0=1;
}
static void params_create(struct wl_client*c,struct wl_resource*r,int32_t w,int32_t h,uint32_t fmt,uint32_t flags){(void)flags;struct dmabuf_params*p=wl_resource_get_user_data(r);struct wl_resource*b=create_dmabuf_buffer(p,c,0,w,h,fmt);if(!b)zwp_linux_buffer_params_v1_send_failed(r);else zwp_linux_buffer_params_v1_send_created(r,b);}
static void params_create_immed(struct wl_client*c,struct wl_resource*r,uint32_t id,int32_t w,int32_t h,uint32_t fmt,uint32_t flags){(void)flags;struct dmabuf_params*p=wl_resource_get_user_data(r);if(!create_dmabuf_buffer(p,c,id,w,h,fmt))wl_resource_post_error(r,ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INVALID_WL_BUFFER,"invalid Vessel dma-buf");}
static const struct zwp_linux_buffer_params_v1_interface params_impl={.destroy=params_destroy,.add=params_add,.create=params_create,.create_immed=params_create_immed};
static void dmabuf_destroy(struct wl_client*c,struct wl_resource*r){(void)c;wl_resource_destroy(r);}
static void dmabuf_create_params(struct wl_client*c,struct wl_resource*r,uint32_t id){struct bridge*b=wl_resource_get_user_data(r);struct dmabuf_params*p=calloc(1,sizeof(*p));if(!p){wl_client_post_no_memory(c);return;}p->bridge=b;p->fd=-1;p->resource=wl_resource_create(c,&zwp_linux_buffer_params_v1_interface,wl_resource_get_version(r),id);if(!p->resource){free(p);wl_client_post_no_memory(c);return;}wl_resource_set_implementation(p->resource,&params_impl,p,params_destroy_resource);}
static const struct zwp_linux_dmabuf_v1_interface dmabuf_impl={.destroy=dmabuf_destroy,.create_params=dmabuf_create_params,.get_default_feedback=NULL,.get_surface_feedback=NULL};
static void bind_dmabuf(struct wl_client*c,void*data,uint32_t version,uint32_t id){
    struct bridge*b=data;uint32_t v=version>3?3:version;struct wl_resource*r=wl_resource_create(c,&zwp_linux_dmabuf_v1_interface,v,id);if(!r){wl_client_post_no_memory(c);return;}wl_resource_set_implementation(r,&dmabuf_impl,b,NULL);
    const uint32_t formats[]={fourcc('X','R','2','4'),fourcc('A','R','2','4'),fourcc('X','B','2','4'),fourcc('A','B','2','4')};
    for(size_t i=0;i<sizeof(formats)/sizeof(formats[0]);++i){zwp_linux_dmabuf_v1_send_format(r,formats[i]);if(v>=3){zwp_linux_dmabuf_v1_send_modifier(r,formats[i],0,0);zwp_linux_dmabuf_v1_send_modifier(r,formats[i],0x00ffffffu,0xffffffffu);}}
}

/* surface/compositor */
static void callback_destroy_resource(struct wl_resource*r){struct frame_callback*cb=wl_resource_get_user_data(r);if(!cb)return;wl_list_remove(&cb->link);free(cb);}
static struct surface *pick_fallback_surface(struct bridge *b, struct surface *skip){struct surface*s;wl_list_for_each_reverse(s,&b->surfaces,link){if(s!=skip&&s->mapped&&s->is_toplevel)return s;}return NULL;}
static void surface_destroy_resource(struct wl_resource*r){
    struct surface*s=wl_resource_get_user_data(r);if(!s)return;struct bridge*b=s->bridge;
    if(b->active==s)activate_surface(b,pick_fallback_surface(b,s));
    struct frame_callback*cb,*ct;wl_list_for_each_safe(cb,ct,&s->callbacks,link)wl_resource_destroy(cb->resource);
    struct present_feedback*pf,*pt;wl_list_for_each_safe(pf,pt,&s->presentation,link){wp_presentation_feedback_send_discarded(pf->resource);wl_resource_destroy(pf->resource);}
    wl_list_remove(&s->link);free(s->title);free(s->app_id);free(s);
}
static void surface_destroy(struct wl_client*c,struct wl_resource*r){(void)c;wl_resource_destroy(r);}
static void surface_attach(struct wl_client*c,struct wl_resource*r,struct wl_resource*buffer,int32_t x,int32_t y){
    (void)c;(void)x;(void)y;struct surface*s=wl_resource_get_user_data(r);s->pending_resource=buffer;s->pending_dmabuf=NULL;s->pending_attached=1;
    if(buffer&&wl_resource_instance_of(buffer,&wl_buffer_interface,&buffer_impl))s->pending_dmabuf=wl_resource_get_user_data(buffer);
}
static void surface_damage(struct wl_client*c,struct wl_resource*r,int32_t x,int32_t y,int32_t w,int32_t h){(void)c;damage_add(wl_resource_get_user_data(r),x,y,w,h);}
static void surface_frame(struct wl_client*c,struct wl_resource*r,uint32_t id){struct surface*s=wl_resource_get_user_data(r);struct frame_callback*cb=calloc(1,sizeof(*cb));if(!cb){wl_client_post_no_memory(c);return;}cb->resource=wl_resource_create(c,&wl_callback_interface,1,id);if(!cb->resource){free(cb);wl_client_post_no_memory(c);return;}wl_list_insert(s->callbacks.prev,&cb->link);wl_resource_set_implementation(cb->resource,NULL,cb,callback_destroy_resource);}
static void surface_region(struct wl_client*c,struct wl_resource*r,struct wl_resource*region){(void)c;(void)r;(void)region;}
static void surface_commit(struct wl_client*c,struct wl_resource*r){
    (void)c;struct surface*s=wl_resource_get_user_data(r);struct bridge*b=s->bridge;
    if(s->pending_attached){
        if(!s->mapped&&(s->is_toplevel||s->is_popup)){s->mapped=1;if(s->is_toplevel)activate_surface(b,s);}
        int visible=(s==b->active)||(s->parent&&s->parent==b->active);
        if(visible&&s->pending_resource){
            if(s->pending_dmabuf){
                s->current_dmabuf=s->pending_dmabuf;
                if(s==b->active&&send_dmabuf_frame(s->current_dmabuf)<0)fprintf(stderr,"[vessel-compositor] dmabuf frame failed: %s\n",strerror(errno));
            }else if(wl_shm_buffer_get(s->pending_resource)){
                if(send_shm_damage(s,s->pending_resource)<0)fprintf(stderr,"[vessel-compositor] SHM damage forward failed\n");
                wl_buffer_send_release(s->pending_resource);
            }
        }
        s->pending_resource=NULL;s->pending_dmabuf=NULL;s->pending_attached=0;s->damage_valid=0;
    }
    if(!wl_list_empty(&s->callbacks)||!wl_list_empty(&s->presentation))schedule_frame_callbacks(b);
}
static void surface_transform(struct wl_client*c,struct wl_resource*r,int32_t t){(void)c;(void)r;(void)t;}
static void surface_scale(struct wl_client*c,struct wl_resource*r,int32_t scale){(void)c;struct surface*s=wl_resource_get_user_data(r);if(scale>0)s->buffer_scale=scale;}
static void surface_damage_buffer(struct wl_client*c,struct wl_resource*r,int32_t x,int32_t y,int32_t w,int32_t h){surface_damage(c,r,x,y,w,h);}
static void surface_offset(struct wl_client*c,struct wl_resource*r,int32_t x,int32_t y){(void)c;(void)r;(void)x;(void)y;}
static const struct wl_surface_interface surface_impl={.destroy=surface_destroy,.attach=surface_attach,.damage=surface_damage,.frame=surface_frame,.set_opaque_region=surface_region,.set_input_region=surface_region,.commit=surface_commit,.set_buffer_transform=surface_transform,.set_buffer_scale=surface_scale,.damage_buffer=surface_damage_buffer,.offset=surface_offset};
static void region_destroy(struct wl_client*c,struct wl_resource*r){(void)c;wl_resource_destroy(r);}static void region_add(struct wl_client*c,struct wl_resource*r,int32_t x,int32_t y,int32_t w,int32_t h){(void)c;(void)r;(void)x;(void)y;(void)w;(void)h;}static void region_subtract(struct wl_client*c,struct wl_resource*r,int32_t x,int32_t y,int32_t w,int32_t h){region_add(c,r,x,y,w,h);}static const struct wl_region_interface region_impl={.destroy=region_destroy,.add=region_add,.subtract=region_subtract};
static void compositor_create_surface(struct wl_client*c,struct wl_resource*r,uint32_t id){struct bridge*b=wl_resource_get_user_data(r);struct surface*s=calloc(1,sizeof(*s));if(!s){wl_client_post_no_memory(c);return;}s->bridge=b;s->buffer_scale=1;wl_list_init(&s->callbacks);wl_list_init(&s->presentation);wl_list_insert(&b->surfaces,&s->link);s->resource=wl_resource_create(c,&wl_surface_interface,wl_resource_get_version(r),id);if(!s->resource){wl_list_remove(&s->link);free(s);wl_client_post_no_memory(c);return;}wl_resource_set_implementation(s->resource,&surface_impl,s,surface_destroy_resource);}
static void compositor_create_region(struct wl_client*c,struct wl_resource*r,uint32_t id){(void)r;struct wl_resource*reg=wl_resource_create(c,&wl_region_interface,1,id);if(!reg){wl_client_post_no_memory(c);return;}wl_resource_set_implementation(reg,&region_impl,NULL,NULL);}
static const struct wl_compositor_interface compositor_impl={.create_surface=compositor_create_surface,.create_region=compositor_create_region};
static void bind_compositor(struct wl_client*c,void*data,uint32_t version,uint32_t id){uint32_t v=version>5?5:version;struct wl_resource*r=wl_resource_create(c,&wl_compositor_interface,v,id);if(!r){wl_client_post_no_memory(c);return;}wl_resource_set_implementation(r,&compositor_impl,data,NULL);}

/* subsurfaces */
static void subsurface_destroy(struct wl_client*c,struct wl_resource*r){(void)c;wl_resource_destroy(r);}static void subsurface_set_position(struct wl_client*c,struct wl_resource*r,int32_t x,int32_t y){(void)c;struct surface*s=wl_resource_get_user_data(r);if(s){s->sub_x=x;s->sub_y=y;}}
static void subsurface_place_above(struct wl_client*c,struct wl_resource*r,struct wl_resource*sib){(void)c;(void)r;(void)sib;}static void subsurface_place_below(struct wl_client*c,struct wl_resource*r,struct wl_resource*sib){(void)c;(void)r;(void)sib;}static void subsurface_set_sync(struct wl_client*c,struct wl_resource*r){(void)c;(void)r;}static void subsurface_set_desync(struct wl_client*c,struct wl_resource*r){(void)c;(void)r;}
static const struct wl_subsurface_interface subsurface_impl={.destroy=subsurface_destroy,.set_position=subsurface_set_position,.place_above=subsurface_place_above,.place_below=subsurface_place_below,.set_sync=subsurface_set_sync,.set_desync=subsurface_set_desync};
static void subcompositor_destroy(struct wl_client*c,struct wl_resource*r){(void)c;wl_resource_destroy(r);}static void subcompositor_get_subsurface(struct wl_client*c,struct wl_resource*r,uint32_t id,struct wl_resource*sr,struct wl_resource*pr){(void)r;struct surface*s=wl_resource_get_user_data(sr);struct surface*p=wl_resource_get_user_data(pr);if(!s||!p){wl_client_post_no_memory(c);return;}s->is_subsurface=1;s->parent=p;struct wl_resource*sub=wl_resource_create(c,&wl_subsurface_interface,1,id);if(!sub){wl_client_post_no_memory(c);return;}wl_resource_set_implementation(sub,&subsurface_impl,s,NULL);}
static const struct wl_subcompositor_interface subcompositor_impl={.destroy=subcompositor_destroy,.get_subsurface=subcompositor_get_subsurface};static void bind_subcompositor(struct wl_client*c,void*data,uint32_t version,uint32_t id){(void)data;uint32_t v=version>1?1:version;struct wl_resource*r=wl_resource_create(c,&wl_subcompositor_interface,v,id);if(!r){wl_client_post_no_memory(c);return;}wl_resource_set_implementation(r,&subcompositor_impl,NULL,NULL);}

/* clipboard */
static void source_unref(struct data_source*s){if(!s)return;if(--s->refs>0)return;struct mime_node*m,*t;wl_list_for_each_safe(m,t,&s->mimes,link){wl_list_remove(&m->link);free(m->mime);free(m);}free(s);}
static void broadcast_selection(struct bridge*b);
static void data_source_resource_destroy(struct wl_resource*r){struct data_source*s=wl_resource_get_user_data(r);if(!s)return;s->resource=NULL;if(s->bridge->selection==s){s->bridge->selection=NULL;broadcast_selection(s->bridge);}source_unref(s);}
static void data_source_offer(struct wl_client*c,struct wl_resource*r,const char*mime){(void)c;struct data_source*s=wl_resource_get_user_data(r);if(!s||!mime)return;struct mime_node*m=calloc(1,sizeof(*m));if(!m)return;m->mime=strdup(mime);if(!m->mime){free(m);return;}wl_list_insert(s->mimes.prev,&m->link);}
static void data_source_destroy_req(struct wl_client*c,struct wl_resource*r){(void)c;wl_resource_destroy(r);}static void data_source_set_actions(struct wl_client*c,struct wl_resource*r,uint32_t a){(void)c;(void)r;(void)a;}static const struct wl_data_source_interface data_source_impl={.offer=data_source_offer,.destroy=data_source_destroy_req,.set_actions=data_source_set_actions};
static void data_offer_accept(struct wl_client*c,struct wl_resource*r,uint32_t serial,const char*mime){(void)c;(void)r;(void)serial;(void)mime;}
static void data_offer_receive(struct wl_client*c,struct wl_resource*r,const char*mime,int32_t fd){(void)c;struct data_offer*o=wl_resource_get_user_data(r);if(o&&o->source&&o->source->resource)wl_data_source_send_send(o->source->resource,mime,fd);close(fd);}static void data_offer_destroy_req(struct wl_client*c,struct wl_resource*r){(void)c;wl_resource_destroy(r);}static void data_offer_finish(struct wl_client*c,struct wl_resource*r){(void)c;(void)r;}static void data_offer_set_actions(struct wl_client*c,struct wl_resource*r,uint32_t a,uint32_t p){(void)c;(void)r;(void)a;(void)p;}
static void data_offer_resource_destroy(struct wl_resource*r){struct data_offer*o=wl_resource_get_user_data(r);if(!o)return;source_unref(o->source);free(o);}static const struct wl_data_offer_interface data_offer_impl={.accept=data_offer_accept,.receive=data_offer_receive,.destroy=data_offer_destroy_req,.finish=data_offer_finish,.set_actions=data_offer_set_actions};
static struct wl_resource*make_offer(struct wl_resource*device,struct data_source*s){if(!s||!s->resource)return NULL;struct wl_client*c=wl_resource_get_client(device);uint32_t v=wl_resource_get_version(device);struct wl_resource*r=wl_resource_create(c,&wl_data_offer_interface,v,0);if(!r)return NULL;struct data_offer*o=calloc(1,sizeof(*o));if(!o){wl_resource_destroy(r);return NULL;}o->source=s;s->refs++;wl_resource_set_implementation(r,&data_offer_impl,o,data_offer_resource_destroy);wl_data_device_send_data_offer(device,r);struct mime_node*m;wl_list_for_each(m,&s->mimes,link)wl_data_offer_send_offer(r,m->mime);return r;}
static void broadcast_selection(struct bridge*b){struct resource_node*n;wl_list_for_each(n,&b->data_devices,link){struct wl_resource*offer=make_offer(n->resource,b->selection);wl_data_device_send_selection(n->resource,offer);}}
static void data_device_start_drag(struct wl_client*c,struct wl_resource*r,struct wl_resource*src,struct wl_resource*origin,struct wl_resource*icon,uint32_t serial){(void)c;(void)r;(void)src;(void)origin;(void)icon;(void)serial;}static void data_device_set_selection(struct wl_client*c,struct wl_resource*r,struct wl_resource*src,uint32_t serial){(void)c;(void)serial;struct resource_node*n=wl_resource_get_user_data(r);struct bridge*b=n?g_bridge:NULL;if(!b)return;b->selection=src?wl_resource_get_user_data(src):NULL;broadcast_selection(b);}static void data_device_release(struct wl_client*c,struct wl_resource*r){(void)c;wl_resource_destroy(r);}static const struct wl_data_device_interface data_device_impl={.start_drag=data_device_start_drag,.set_selection=data_device_set_selection,.release=data_device_release};
static void resource_node_destroy(struct wl_resource*r){struct resource_node*n=wl_resource_get_user_data(r);if(!n)return;wl_list_remove(&n->link);free(n);}static void ddm_create_data_source(struct wl_client*c,struct wl_resource*r,uint32_t id){struct bridge*b=wl_resource_get_user_data(r);struct data_source*s=calloc(1,sizeof(*s));if(!s){wl_client_post_no_memory(c);return;}s->bridge=b;s->refs=1;wl_list_init(&s->mimes);s->resource=wl_resource_create(c,&wl_data_source_interface,wl_resource_get_version(r),id);if(!s->resource){free(s);wl_client_post_no_memory(c);return;}wl_resource_set_implementation(s->resource,&data_source_impl,s,data_source_resource_destroy);}
static void ddm_get_data_device(struct wl_client*c,struct wl_resource*r,uint32_t id,struct wl_resource*seat){(void)seat;struct bridge*b=wl_resource_get_user_data(r);struct resource_node*n=calloc(1,sizeof(*n));if(!n){wl_client_post_no_memory(c);return;}n->resource=wl_resource_create(c,&wl_data_device_interface,wl_resource_get_version(r),id);if(!n->resource){free(n);wl_client_post_no_memory(c);return;}wl_list_insert(b->data_devices.prev,&n->link);wl_resource_set_implementation(n->resource,&data_device_impl,n,resource_node_destroy);struct wl_resource*offer=make_offer(n->resource,b->selection);wl_data_device_send_selection(n->resource,offer);}
static const struct wl_data_device_manager_interface ddm_impl={.create_data_source=ddm_create_data_source,.get_data_device=ddm_get_data_device};static void bind_data_device_manager(struct wl_client*c,void*data,uint32_t version,uint32_t id){uint32_t v=version>3?3:version;struct wl_resource*r=wl_resource_create(c,&wl_data_device_manager_interface,v,id);if(!r){wl_client_post_no_memory(c);return;}wl_resource_set_implementation(r,&ddm_impl,data,NULL);}

/* seat/input */
static int client_is_active(struct bridge*b,struct wl_resource*r){return b->active&&wl_resource_get_client(r)==wl_resource_get_client(b->active->resource);}
static void send_pointer_focus(struct bridge*b,struct surface*old,struct surface*now){struct resource_node*n;wl_list_for_each(n,&b->pointers,link){struct wl_client*cl=wl_resource_get_client(n->resource);if(old&&cl==wl_resource_get_client(old->resource))wl_pointer_send_leave(n->resource,wl_display_next_serial(b->display),old->resource);if(now&&cl==wl_resource_get_client(now->resource))wl_pointer_send_enter(n->resource,wl_display_next_serial(b->display),now->resource,wl_fixed_from_double(b->pointer_x),wl_fixed_from_double(b->pointer_y));if(wl_resource_get_version(n->resource)>=5)wl_pointer_send_frame(n->resource);}}
static void send_keyboard_focus(struct bridge*b,struct surface*old,struct surface*now){struct resource_node*n;wl_list_for_each(n,&b->keyboards,link){struct wl_client*cl=wl_resource_get_client(n->resource);if(old&&cl==wl_resource_get_client(old->resource))wl_keyboard_send_leave(n->resource,wl_display_next_serial(b->display),old->resource);if(now&&cl==wl_resource_get_client(now->resource)){struct wl_array keys;wl_array_init(&keys);wl_keyboard_send_enter(n->resource,wl_display_next_serial(b->display),now->resource,&keys);wl_array_release(&keys);}}}
static void send_text_focus(struct bridge*b,struct surface*old,struct surface*now){struct text_input*t;wl_list_for_each(t,&b->text_inputs,link){struct wl_client*cl=wl_resource_get_client(t->resource);if(old&&cl==wl_resource_get_client(old->resource))zwp_text_input_v3_send_leave(t->resource,old->resource);if(now&&cl==wl_resource_get_client(now->resource))zwp_text_input_v3_send_enter(t->resource,now->resource);}}
static void configure_toplevel(struct surface*s,int active){if(!s||!s->xdg_toplevel||!s->xdg_surface)return;struct wl_array states;wl_array_init(&states);uint32_t*st=wl_array_add(&states,sizeof(uint32_t));if(st)*st=XDG_TOPLEVEL_STATE_MAXIMIZED;if(active){st=wl_array_add(&states,sizeof(uint32_t));if(st)*st=XDG_TOPLEVEL_STATE_ACTIVATED;}xdg_toplevel_send_configure(s->xdg_toplevel,s->bridge->width,s->bridge->height,&states);wl_array_release(&states);xdg_surface_send_configure(s->xdg_surface,wl_display_next_serial(s->bridge->display));}
static void activate_surface(struct bridge*b,struct surface*s){if(b->active==s)return;struct surface*old=b->active;b->active=s;if(old)configure_toplevel(old,0);if(s)configure_toplevel(s,1);send_pointer_focus(b,old,s);send_keyboard_focus(b,old,s);send_text_focus(b,old,s);if(old)finish_presentation(old,0);fprintf(stderr,"[vessel-compositor] focus %s\n",s?(s->title?s->title:(s->app_id?s->app_id:"surface")):"none");}
static void pointer_set_cursor(struct wl_client*c,struct wl_resource*r,uint32_t serial,struct wl_resource*surface,int32_t x,int32_t y){(void)c;(void)r;(void)serial;(void)surface;(void)x;(void)y;}static void pointer_release(struct wl_client*c,struct wl_resource*r){(void)c;wl_resource_destroy(r);}static const struct wl_pointer_interface pointer_impl={.set_cursor=pointer_set_cursor,.release=pointer_release};
static void keyboard_release(struct wl_client*c,struct wl_resource*r){(void)c;wl_resource_destroy(r);}static const struct wl_keyboard_interface keyboard_impl={.release=keyboard_release};
static void touch_release(struct wl_client*c,struct wl_resource*r){(void)c;wl_resource_destroy(r);}static const struct wl_touch_interface touch_impl={.release=touch_release};
static struct resource_node*new_resource_node(struct bridge*b,struct wl_list*list,struct wl_client*c,const struct wl_interface*iface,uint32_t version,uint32_t id,const void*impl){struct resource_node*n=calloc(1,sizeof(*n));if(!n){wl_client_post_no_memory(c);return NULL;}n->resource=wl_resource_create(c,iface,version,id);if(!n->resource){free(n);wl_client_post_no_memory(c);return NULL;}wl_list_insert(list->prev,&n->link);wl_resource_set_implementation(n->resource,impl,n,resource_node_destroy);(void)b;return n;}
static void seat_get_pointer(struct wl_client*c,struct wl_resource*r,uint32_t id){struct bridge*b=wl_resource_get_user_data(r);struct resource_node*n=new_resource_node(b,&b->pointers,c,&wl_pointer_interface,wl_resource_get_version(r),id,&pointer_impl);if(n&&client_is_active(b,n->resource))wl_pointer_send_enter(n->resource,wl_display_next_serial(b->display),b->active->resource,wl_fixed_from_double(b->pointer_x),wl_fixed_from_double(b->pointer_y));}
static void seat_get_keyboard(struct wl_client*c,struct wl_resource*r,uint32_t id){struct bridge*b=wl_resource_get_user_data(r);struct resource_node*n=new_resource_node(b,&b->keyboards,c,&wl_keyboard_interface,wl_resource_get_version(r),id,&keyboard_impl);if(!n)return;if(b->keymap_fd>=0)wl_keyboard_send_keymap(n->resource,WL_KEYBOARD_KEYMAP_FORMAT_XKB_V1,b->keymap_fd,(uint32_t)b->keymap_size);if(wl_resource_get_version(n->resource)>=4)wl_keyboard_send_repeat_info(n->resource,30,400);if(client_is_active(b,n->resource)){struct wl_array keys;wl_array_init(&keys);wl_keyboard_send_enter(n->resource,wl_display_next_serial(b->display),b->active->resource,&keys);wl_array_release(&keys);}}
static void seat_get_touch(struct wl_client*c,struct wl_resource*r,uint32_t id){struct bridge*b=wl_resource_get_user_data(r);new_resource_node(b,&b->touches,c,&wl_touch_interface,wl_resource_get_version(r),id,&touch_impl);}
static void seat_release(struct wl_client*c,struct wl_resource*r){(void)c;wl_resource_destroy(r);}static const struct wl_seat_interface seat_impl={.get_pointer=seat_get_pointer,.get_keyboard=seat_get_keyboard,.get_touch=seat_get_touch,.release=seat_release};
static void bind_seat(struct wl_client*c,void*data,uint32_t version,uint32_t id){struct bridge*b=data;uint32_t v=version>7?7:version;struct wl_resource*r=wl_resource_create(c,&wl_seat_interface,v,id);if(!r){wl_client_post_no_memory(c);return;}wl_resource_set_implementation(r,&seat_impl,b,NULL);if(v>=2)wl_seat_send_name(r,"vessel-seat0");wl_seat_send_capabilities(r,WL_SEAT_CAPABILITY_POINTER|WL_SEAT_CAPABILITY_KEYBOARD|WL_SEAT_CAPABILITY_TOUCH);}

/* text-input-v3 */
static void text_input_destroy_resource(struct wl_resource*r){struct text_input*t=wl_resource_get_user_data(r);if(!t)return;wl_list_remove(&t->link);free(t);}static void ti_destroy(struct wl_client*c,struct wl_resource*r){(void)c;wl_resource_destroy(r);}static void ti_enable(struct wl_client*c,struct wl_resource*r){(void)c;struct text_input*t=wl_resource_get_user_data(r);if(t)t->enabled=1;}static void ti_disable(struct wl_client*c,struct wl_resource*r){(void)c;struct text_input*t=wl_resource_get_user_data(r);if(t)t->enabled=0;}static void ti_surround(struct wl_client*c,struct wl_resource*r,const char*text,int32_t cursor,int32_t anchor){(void)c;(void)r;(void)text;(void)cursor;(void)anchor;}static void ti_cause(struct wl_client*c,struct wl_resource*r,uint32_t cause){(void)c;(void)r;(void)cause;}static void ti_content(struct wl_client*c,struct wl_resource*r,uint32_t hint,uint32_t purpose){(void)c;(void)r;(void)hint;(void)purpose;}static void ti_cursor(struct wl_client*c,struct wl_resource*r,int32_t x,int32_t y,int32_t w,int32_t h){(void)c;(void)r;(void)x;(void)y;(void)w;(void)h;}static void ti_commit(struct wl_client*c,struct wl_resource*r){(void)c;(void)r;}
static const struct zwp_text_input_v3_interface text_input_impl={.destroy=ti_destroy,.enable=ti_enable,.disable=ti_disable,.set_surrounding_text=ti_surround,.set_text_change_cause=ti_cause,.set_content_type=ti_content,.set_cursor_rectangle=ti_cursor,.commit=ti_commit};
static void tim_destroy(struct wl_client*c,struct wl_resource*r){(void)c;wl_resource_destroy(r);}static void tim_get(struct wl_client*c,struct wl_resource*r,uint32_t id,struct wl_resource*seat){(void)seat;struct bridge*b=wl_resource_get_user_data(r);struct text_input*t=calloc(1,sizeof(*t));if(!t){wl_client_post_no_memory(c);return;}t->bridge=b;t->resource=wl_resource_create(c,&zwp_text_input_v3_interface,1,id);if(!t->resource){free(t);wl_client_post_no_memory(c);return;}wl_list_insert(b->text_inputs.prev,&t->link);wl_resource_set_implementation(t->resource,&text_input_impl,t,text_input_destroy_resource);if(b->active&&wl_resource_get_client(t->resource)==wl_resource_get_client(b->active->resource))zwp_text_input_v3_send_enter(t->resource,b->active->resource);}
static const struct zwp_text_input_manager_v3_interface tim_impl={.destroy=tim_destroy,.get_text_input=tim_get};static void bind_text_input_manager(struct wl_client*c,void*data,uint32_t version,uint32_t id){(void)version;struct wl_resource*r=wl_resource_create(c,&zwp_text_input_manager_v3_interface,1,id);if(!r){wl_client_post_no_memory(c);return;}wl_resource_set_implementation(r,&tim_impl,data,NULL);}

/* xdg shell */
static void xdg_toplevel_destroy(struct wl_client*c,struct wl_resource*r){(void)c;wl_resource_destroy(r);}static void xdg_set_parent(struct wl_client*c,struct wl_resource*r,struct wl_resource*p){(void)c;(void)r;(void)p;}static void xdg_set_title(struct wl_client*c,struct wl_resource*r,const char*title){(void)c;struct surface*s=wl_resource_get_user_data(r);if(s){free(s->title);s->title=strdup(title?title:"");}}static void xdg_set_appid(struct wl_client*c,struct wl_resource*r,const char*id){(void)c;struct surface*s=wl_resource_get_user_data(r);if(s){free(s->app_id);s->app_id=strdup(id?id:"");}}static void noop_menu(struct wl_client*c,struct wl_resource*r,struct wl_resource*seat,uint32_t serial,int32_t x,int32_t y){(void)c;(void)r;(void)seat;(void)serial;(void)x;(void)y;}static void noop_move(struct wl_client*c,struct wl_resource*r,struct wl_resource*seat,uint32_t serial){(void)c;(void)r;(void)seat;(void)serial;}static void noop_resize(struct wl_client*c,struct wl_resource*r,struct wl_resource*seat,uint32_t serial,uint32_t edges){(void)c;(void)r;(void)seat;(void)serial;(void)edges;}static void noop_size(struct wl_client*c,struct wl_resource*r,int32_t w,int32_t h){(void)c;(void)r;(void)w;(void)h;}static void xdg_max(struct wl_client*c,struct wl_resource*r){(void)c;configure_toplevel(wl_resource_get_user_data(r),1);}static void xdg_unmax(struct wl_client*c,struct wl_resource*r){(void)c;configure_toplevel(wl_resource_get_user_data(r),1);}static void xdg_full(struct wl_client*c,struct wl_resource*r,struct wl_resource*out){(void)c;(void)out;configure_toplevel(wl_resource_get_user_data(r),1);}static void xdg_unfull(struct wl_client*c,struct wl_resource*r){(void)c;configure_toplevel(wl_resource_get_user_data(r),1);}static void xdg_min(struct wl_client*c,struct wl_resource*r){(void)c;struct surface*s=wl_resource_get_user_data(r);if(s&&s->bridge->active==s)activate_surface(s->bridge,pick_fallback_surface(s->bridge,s));}
static const struct xdg_toplevel_interface toplevel_impl={.destroy=xdg_toplevel_destroy,.set_parent=xdg_set_parent,.set_title=xdg_set_title,.set_app_id=xdg_set_appid,.show_window_menu=noop_menu,.move=noop_move,.resize=noop_resize,.set_max_size=noop_size,.set_min_size=noop_size,.set_maximized=xdg_max,.unset_maximized=xdg_unmax,.set_fullscreen=xdg_full,.unset_fullscreen=xdg_unfull,.set_minimized=xdg_min};
static void xdg_surface_destroy(struct wl_client*c,struct wl_resource*r){(void)c;wl_resource_destroy(r);}static void xdg_surface_get_toplevel(struct wl_client*c,struct wl_resource*r,uint32_t id){struct surface*s=wl_resource_get_user_data(r);s->is_toplevel=1;s->xdg_surface=r;struct wl_resource*top=wl_resource_create(c,&xdg_toplevel_interface,1,id);if(!top){wl_client_post_no_memory(c);return;}s->xdg_toplevel=top;wl_resource_set_implementation(top,&toplevel_impl,s,NULL);configure_toplevel(s,1);}
static void xdg_surface_get_popup(struct wl_client*c,struct wl_resource*r,uint32_t id,struct wl_resource*parent,struct wl_resource*posres);
static void xdg_surface_geometry(struct wl_client*c,struct wl_resource*r,int32_t x,int32_t y,int32_t w,int32_t h){(void)c;(void)r;(void)x;(void)y;(void)w;(void)h;}static void xdg_surface_ack(struct wl_client*c,struct wl_resource*r,uint32_t serial){(void)c;(void)r;(void)serial;}static const struct xdg_surface_interface xdg_surface_impl={.destroy=xdg_surface_destroy,.get_toplevel=xdg_surface_get_toplevel,.get_popup=xdg_surface_get_popup,.set_window_geometry=xdg_surface_geometry,.ack_configure=xdg_surface_ack};
static void positioner_destroy_resource(struct wl_resource*r){free(wl_resource_get_user_data(r));}static void pos_destroy(struct wl_client*c,struct wl_resource*r){(void)c;wl_resource_destroy(r);}static void pos_size(struct wl_client*c,struct wl_resource*r,int32_t w,int32_t h){(void)c;struct positioner*p=wl_resource_get_user_data(r);p->width=w;p->height=h;}static void pos_anchor_rect(struct wl_client*c,struct wl_resource*r,int32_t x,int32_t y,int32_t w,int32_t h){(void)c;struct positioner*p=wl_resource_get_user_data(r);p->anchor_x=x;p->anchor_y=y;p->anchor_w=w;p->anchor_h=h;}static void pos_u32(struct wl_client*c,struct wl_resource*r,uint32_t v){(void)c;(void)r;(void)v;}static void pos_offset(struct wl_client*c,struct wl_resource*r,int32_t x,int32_t y){(void)c;struct positioner*p=wl_resource_get_user_data(r);p->offset_x=x;p->offset_y=y;}static void pos_reactive(struct wl_client*c,struct wl_resource*r){(void)c;(void)r;}static void pos_parent_size(struct wl_client*c,struct wl_resource*r,int32_t w,int32_t h){(void)c;(void)r;(void)w;(void)h;}static void pos_parent_config(struct wl_client*c,struct wl_resource*r,uint32_t serial){(void)c;(void)r;(void)serial;}
static const struct xdg_positioner_interface positioner_impl={.destroy=pos_destroy,.set_size=pos_size,.set_anchor_rect=pos_anchor_rect,.set_anchor=pos_u32,.set_gravity=pos_u32,.set_constraint_adjustment=pos_u32,.set_offset=pos_offset,.set_reactive=pos_reactive,.set_parent_size=pos_parent_size,.set_parent_configure=pos_parent_config};
static void popup_destroy(struct wl_client*c,struct wl_resource*r){(void)c;wl_resource_destroy(r);}static void popup_grab(struct wl_client*c,struct wl_resource*r,struct wl_resource*seat,uint32_t serial){(void)c;(void)r;(void)seat;(void)serial;}static void popup_reposition(struct wl_client*c,struct wl_resource*r,struct wl_resource*pos,uint32_t token){(void)c;struct surface*s=wl_resource_get_user_data(r);struct positioner*p=wl_resource_get_user_data(pos);if(s&&p){s->sub_x=p->anchor_x+p->offset_x;s->sub_y=p->anchor_y+p->offset_y;xdg_popup_send_repositioned(r,token);}}
static const struct xdg_popup_interface popup_impl={.destroy=popup_destroy,.grab=popup_grab,.reposition=popup_reposition};
static void xdg_surface_get_popup(struct wl_client*c,struct wl_resource*r,uint32_t id,struct wl_resource*parent,struct wl_resource*posres){struct surface*s=wl_resource_get_user_data(r);struct positioner*p=wl_resource_get_user_data(posres);s->is_popup=1;s->xdg_surface=r;if(parent)s->parent=wl_resource_get_user_data(parent);if(p){s->sub_x=p->anchor_x+p->offset_x;s->sub_y=p->anchor_y+p->offset_y;}struct wl_resource*pop=wl_resource_create(c,&xdg_popup_interface,3,id);if(!pop){wl_client_post_no_memory(c);return;}wl_resource_set_implementation(pop,&popup_impl,s,NULL);int w=p&&p->width>0?p->width:1,h=p&&p->height>0?p->height:1;xdg_popup_send_configure(pop,s->sub_x,s->sub_y,w,h);xdg_surface_send_configure(r,wl_display_next_serial(s->bridge->display));}
static void wm_destroy(struct wl_client*c,struct wl_resource*r){(void)c;wl_resource_destroy(r);}static void wm_create_positioner(struct wl_client*c,struct wl_resource*r,uint32_t id){(void)r;struct positioner*p=calloc(1,sizeof(*p));if(!p){wl_client_post_no_memory(c);return;}struct wl_resource*pos=wl_resource_create(c,&xdg_positioner_interface,3,id);if(!pos){free(p);wl_client_post_no_memory(c);return;}wl_resource_set_implementation(pos,&positioner_impl,p,positioner_destroy_resource);}static void wm_get_xdg_surface(struct wl_client*c,struct wl_resource*r,uint32_t id,struct wl_resource*surface_res){struct surface*s=wl_resource_get_user_data(surface_res);if(!s){wl_resource_post_error(r,XDG_WM_BASE_ERROR_INVALID_SURFACE_STATE,"invalid surface");return;}struct wl_resource*xdg=wl_resource_create(c,&xdg_surface_interface,wl_resource_get_version(r),id);if(!xdg){wl_client_post_no_memory(c);return;}s->xdg_surface=xdg;wl_resource_set_implementation(xdg,&xdg_surface_impl,s,NULL);}static void wm_pong(struct wl_client*c,struct wl_resource*r,uint32_t serial){(void)c;(void)r;(void)serial;}static const struct xdg_wm_base_interface wm_impl={.destroy=wm_destroy,.create_positioner=wm_create_positioner,.get_xdg_surface=wm_get_xdg_surface,.pong=wm_pong};static void bind_wm(struct wl_client*c,void*data,uint32_t version,uint32_t id){uint32_t v=version>3?3:version;struct wl_resource*r=wl_resource_create(c,&xdg_wm_base_interface,v,id);if(!r){wl_client_post_no_memory(c);return;}wl_resource_set_implementation(r,&wm_impl,data,NULL);}

/* client-side decorations */
static void deco_destroy(struct wl_client*c,struct wl_resource*r){(void)c;wl_resource_destroy(r);}static void deco_set_mode(struct wl_client*c,struct wl_resource*r,uint32_t mode){(void)c;(void)mode;zxdg_toplevel_decoration_v1_send_configure(r,ZXDG_TOPLEVEL_DECORATION_V1_MODE_CLIENT_SIDE);}static void deco_unset_mode(struct wl_client*c,struct wl_resource*r){(void)c;zxdg_toplevel_decoration_v1_send_configure(r,ZXDG_TOPLEVEL_DECORATION_V1_MODE_CLIENT_SIDE);}static const struct zxdg_toplevel_decoration_v1_interface deco_impl={.destroy=deco_destroy,.set_mode=deco_set_mode,.unset_mode=deco_unset_mode};static void deco_mgr_destroy(struct wl_client*c,struct wl_resource*r){(void)c;wl_resource_destroy(r);}static void deco_mgr_get(struct wl_client*c,struct wl_resource*r,uint32_t id,struct wl_resource*top){(void)r;(void)top;struct wl_resource*d=wl_resource_create(c,&zxdg_toplevel_decoration_v1_interface,1,id);if(!d){wl_client_post_no_memory(c);return;}wl_resource_set_implementation(d,&deco_impl,NULL,NULL);zxdg_toplevel_decoration_v1_send_configure(d,ZXDG_TOPLEVEL_DECORATION_V1_MODE_CLIENT_SIDE);}static const struct zxdg_decoration_manager_v1_interface deco_mgr_impl={.destroy=deco_mgr_destroy,.get_toplevel_decoration=deco_mgr_get};static void bind_deco_mgr(struct wl_client*c,void*data,uint32_t version,uint32_t id){(void)data;(void)version;struct wl_resource*r=wl_resource_create(c,&zxdg_decoration_manager_v1_interface,1,id);if(!r){wl_client_post_no_memory(c);return;}wl_resource_set_implementation(r,&deco_mgr_impl,NULL,NULL);}

/* presentation-time */
static void presentation_destroy(struct wl_client*c,struct wl_resource*r){(void)c;wl_resource_destroy(r);}static void presentation_feedback(struct wl_client*c,struct wl_resource*r,struct wl_resource*surface_res,uint32_t id){struct surface*s=wl_resource_get_user_data(surface_res);if(!s){return;}struct present_feedback*pf=calloc(1,sizeof(*pf));if(!pf){wl_client_post_no_memory(c);return;}pf->resource=wl_resource_create(c,&wp_presentation_feedback_interface,1,id);if(!pf->resource){free(pf);wl_client_post_no_memory(c);return;}wl_list_insert(s->presentation.prev,&pf->link);wl_resource_set_implementation(pf->resource,&presentation_feedback_impl,pf,presentation_feedback_destroy_resource);}static const struct wp_presentation_interface presentation_impl={.destroy=presentation_destroy,.feedback=presentation_feedback};static void bind_presentation(struct wl_client*c,void*data,uint32_t version,uint32_t id){(void)data;uint32_t v=version>1?1:version;struct wl_resource*r=wl_resource_create(c,&wp_presentation_interface,v,id);if(!r){wl_client_post_no_memory(c);return;}wl_resource_set_implementation(r,&presentation_impl,NULL,NULL);wp_presentation_send_clock_id(r,CLOCK_MONOTONIC);}

/* output */
static void output_release(struct wl_client*c,struct wl_resource*r){(void)c;wl_resource_destroy(r);}static const struct wl_output_interface output_impl={.release=output_release};static void bind_output(struct wl_client*c,void*data,uint32_t version,uint32_t id){struct bridge*b=data;uint32_t v=version>4?4:version;struct wl_resource*r=wl_resource_create(c,&wl_output_interface,v,id);if(!r){wl_client_post_no_memory(c);return;}wl_resource_set_implementation(r,&output_impl,b,NULL);wl_output_send_geometry(r,0,0,0,0,WL_OUTPUT_SUBPIXEL_UNKNOWN,"Vessel","Android Surface",WL_OUTPUT_TRANSFORM_NORMAL);wl_output_send_mode(r,WL_OUTPUT_MODE_CURRENT|WL_OUTPUT_MODE_PREFERRED,b->width,b->height,b->refresh_mhz);if(v>=2)wl_output_send_scale(r,1);if(v>=4){wl_output_send_name(r,"Vessel-0");wl_output_send_description(r,"Vessel Android native Vulkan output");}if(v>=2)wl_output_send_done(r);}

/* input JSON */
static int json_int(const char*line,const char*key,int def){char pat[64];snprintf(pat,sizeof(pat),"\"%s\":",key);const char*p=strstr(line,pat);if(!p)return def;p+=strlen(pat);return (int)strtol(p,NULL,10);}static int json_bool(const char*line,const char*key,int def){char pat[64];snprintf(pat,sizeof(pat),"\"%s\":",key);const char*p=strstr(line,pat);if(!p)return def;p+=strlen(pat);return strncmp(p,"true",4)==0?1:strncmp(p,"false",5)==0?0:def;}static int json_type(const char*line,char*out,size_t cap){const char*p=strstr(line,"\"t\":\"");if(!p)return 0;p+=5;const char*e=strchr(p,'\"');if(!e)return 0;size_t n=(size_t)(e-p);if(n>=cap)n=cap-1;memcpy(out,p,n);out[n]=0;return 1;}
static int b64v(int c){if(c>='A'&&c<='Z')return c-'A';if(c>='a'&&c<='z')return c-'a'+26;if(c>='0'&&c<='9')return c-'0'+52;if(c=='+')return 62;if(c=='/')return 63;return -1;}static char*json_b64_decode(const char*line){const char*p=strstr(line,"\"b64\":\"");if(!p)return NULL;p+=7;const char*e=strchr(p,'\"');if(!e)return NULL;size_t in=(size_t)(e-p),cap=in*3/4+4;char*out=calloc(1,cap+1);if(!out)return NULL;size_t oi=0;int val=0,bits=-8;for(size_t i=0;i<in;i++){if(p[i]=='=')break;int v=b64v((unsigned char)p[i]);if(v<0)continue;val=(val<<6)|v;bits+=6;if(bits>=0){out[oi++]=(char)((val>>bits)&0xff);bits-=8;}}out[oi]=0;return out;}
static void send_pointer_motion(struct bridge*b){if(!b->active)return;uint32_t tm=monotonic_ms();struct resource_node*n;wl_list_for_each(n,&b->pointers,link)if(client_is_active(b,n->resource)){wl_pointer_send_motion(n->resource,tm,wl_fixed_from_double(b->pointer_x),wl_fixed_from_double(b->pointer_y));if(wl_resource_get_version(n->resource)>=5)wl_pointer_send_frame(n->resource);}}
static void handle_input_line(struct bridge*b,const char*line){char t[16];if(!json_type(line,t,sizeof(t)))return;if(strcmp(t,"rel")==0){b->pointer_x+=json_int(line,"dx",0);b->pointer_y+=json_int(line,"dy",0);if(b->pointer_x<0)b->pointer_x=0;if(b->pointer_y<0)b->pointer_y=0;if(b->pointer_x>b->width-1)b->pointer_x=b->width-1;if(b->pointer_y>b->height-1)b->pointer_y=b->height-1;send_pointer_motion(b);}else if(strcmp(t,"btn")==0){if(!b->active)return;uint32_t button=(uint32_t)json_int(line,"code",0x110),state=json_bool(line,"down",0)?WL_POINTER_BUTTON_STATE_PRESSED:WL_POINTER_BUTTON_STATE_RELEASED,serial=wl_display_next_serial(b->display),tm=monotonic_ms();struct resource_node*n;wl_list_for_each(n,&b->pointers,link)if(client_is_active(b,n->resource)){wl_pointer_send_button(n->resource,serial,tm,button,state);if(wl_resource_get_version(n->resource)>=5)wl_pointer_send_frame(n->resource);}}else if(strcmp(t,"scroll")==0){if(!b->active)return;int x=json_int(line,"x",0),y=json_int(line,"y",0);uint32_t tm=monotonic_ms();struct resource_node*n;wl_list_for_each(n,&b->pointers,link)if(client_is_active(b,n->resource)){uint32_t v=wl_resource_get_version(n->resource);if(v>=5)wl_pointer_send_axis_source(n->resource,WL_POINTER_AXIS_SOURCE_FINGER);if(y){wl_pointer_send_axis(n->resource,tm,WL_POINTER_AXIS_VERTICAL_SCROLL,wl_fixed_from_int(-y*18));if(v>=5)wl_pointer_send_axis_discrete(n->resource,WL_POINTER_AXIS_VERTICAL_SCROLL,-y);}if(x){wl_pointer_send_axis(n->resource,tm,WL_POINTER_AXIS_HORIZONTAL_SCROLL,wl_fixed_from_int(-x*18));if(v>=5)wl_pointer_send_axis_discrete(n->resource,WL_POINTER_AXIS_HORIZONTAL_SCROLL,-x);}if(v>=5)wl_pointer_send_frame(n->resource);}}else if(strcmp(t,"abs")==0){if(!b->active)return;double x=(double)json_int(line,"x",0)/32767.0*(b->width-1),y=(double)json_int(line,"y",0)/32767.0*(b->height-1);int down=json_bool(line,"down",0);uint32_t tm=monotonic_ms();struct resource_node*n;wl_list_for_each(n,&b->touches,link)if(client_is_active(b,n->resource)){if(down&&!b->touch_down)wl_touch_send_down(n->resource,wl_display_next_serial(b->display),tm,b->active->resource,0,wl_fixed_from_double(x),wl_fixed_from_double(y));else if(down)wl_touch_send_motion(n->resource,tm,0,wl_fixed_from_double(x),wl_fixed_from_double(y));else if(b->touch_down)wl_touch_send_up(n->resource,wl_display_next_serial(b->display),tm,0);wl_touch_send_frame(n->resource);}b->touch_down=down;}else if(strcmp(t,"key")==0){if(!b->active)return;uint32_t code=(uint32_t)json_int(line,"code",0);int down=json_bool(line,"down",0);uint32_t serial=wl_display_next_serial(b->display),tm=monotonic_ms();struct resource_node*n;wl_list_for_each(n,&b->keyboards,link)if(client_is_active(b,n->resource))wl_keyboard_send_key(n->resource,serial,tm,code,down?WL_KEYBOARD_KEY_STATE_PRESSED:WL_KEYBOARD_KEY_STATE_RELEASED);if(b->xkb_state&&code){xkb_state_update_key(b->xkb_state,code+8,down?XKB_KEY_DOWN:XKB_KEY_UP);xkb_mod_mask_t dep=xkb_state_serialize_mods(b->xkb_state,XKB_STATE_MODS_DEPRESSED),lat=xkb_state_serialize_mods(b->xkb_state,XKB_STATE_MODS_LATCHED),lock=xkb_state_serialize_mods(b->xkb_state,XKB_STATE_MODS_LOCKED);xkb_layout_index_t grp=xkb_state_serialize_layout(b->xkb_state,XKB_STATE_LAYOUT_EFFECTIVE);wl_list_for_each(n,&b->keyboards,link)if(client_is_active(b,n->resource))wl_keyboard_send_modifiers(n->resource,serial,dep,lat,lock,grp);}}else if(strcmp(t,"text")==0){char*txt=json_b64_decode(line);if(!txt)return;struct text_input*ti;wl_list_for_each(ti,&b->text_inputs,link)if(ti->enabled&&client_is_active(b,ti->resource)){zwp_text_input_v3_send_commit_string(ti->resource,txt);zwp_text_input_v3_send_done(ti->resource,++b->text_serial);}free(txt);}}
static int input_fd_handler(int fd,uint32_t mask,void*data){struct bridge*b=data;if(mask&(WL_EVENT_HANGUP|WL_EVENT_ERROR)){if(b->input_source){wl_event_source_remove(b->input_source);b->input_source=NULL;}close(fd);b->input_fd=-1;wl_event_source_timer_update(b->input_timer,150);return 0;}char tmp[4096];ssize_t n=recv(fd,tmp,sizeof(tmp),0);if(n<=0){if(b->input_source){wl_event_source_remove(b->input_source);b->input_source=NULL;}close(fd);b->input_fd=-1;wl_event_source_timer_update(b->input_timer,150);return 0;}for(ssize_t i=0;i<n;i++){char ch=tmp[i];if(ch=='\n'){b->input_buf[b->input_len]=0;handle_input_line(b,b->input_buf);b->input_len=0;}else if(b->input_len+1<sizeof(b->input_buf))b->input_buf[b->input_len++]=ch;else b->input_len=0;}return 0;}
static int input_reconnect(void*data){struct bridge*b=data;if(b->input_fd>=0)return 0;int fd=socket(AF_INET,SOCK_STREAM|SOCK_CLOEXEC,0);if(fd<0){wl_event_source_timer_update(b->input_timer,500);return 0;}struct sockaddr_in a={0};a.sin_family=AF_INET;a.sin_port=htons((uint16_t)b->input_port);if(inet_pton(AF_INET,b->input_host,&a.sin_addr)!=1||connect(fd,(struct sockaddr*)&a,sizeof(a))<0){close(fd);wl_event_source_timer_update(b->input_timer,250);return 0;}int fl=fcntl(fd,F_GETFL,0);fcntl(fd,F_SETFL,fl|O_NONBLOCK);int one=1;setsockopt(fd,IPPROTO_TCP,TCP_NODELAY,&one,sizeof(one));b->input_fd=fd;b->input_source=wl_event_loop_add_fd(b->loop,fd,WL_EVENT_READABLE|WL_EVENT_HANGUP|WL_EVENT_ERROR,input_fd_handler,b);fprintf(stderr,"[vessel-compositor] direct Android input connected %s:%d\n",b->input_host,b->input_port);return 0;}

static int init_keymap(struct bridge*b){b->xkb_context=xkb_context_new(XKB_CONTEXT_NO_FLAGS);if(!b->xkb_context)return -1;struct xkb_rule_names names={.layout="us"};b->xkb_keymap=xkb_keymap_new_from_names(b->xkb_context,&names,XKB_KEYMAP_COMPILE_NO_FLAGS);if(!b->xkb_keymap)return -1;b->xkb_state=xkb_state_new(b->xkb_keymap);if(!b->xkb_state)return -1;char*map=xkb_keymap_get_as_string(b->xkb_keymap,XKB_KEYMAP_FORMAT_TEXT_V1);if(!map)return -1;b->keymap_size=strlen(map)+1;b->keymap_fd=memfd_create("vessel-keymap",MFD_CLOEXEC);if(b->keymap_fd<0){free(map);return -1;}if(ftruncate(b->keymap_fd,(off_t)b->keymap_size)<0||write(b->keymap_fd,map,b->keymap_size)!=(ssize_t)b->keymap_size){free(map);return -1;}free(map);return 0;}

static void on_signal(int sig){(void)sig;if(g_bridge&&g_bridge->display)wl_display_terminate(g_bridge->display);}

int main(int argc,char**argv){
    struct bridge b={.width=1600,.height=720,.refresh_mhz=60000,.frame_fd=-1,.input_fd=-1,.input_port=47633,.keymap_fd=-1};
    wl_list_init(&b.surfaces);wl_list_init(&b.pointers);wl_list_init(&b.keyboards);wl_list_init(&b.touches);wl_list_init(&b.data_devices);wl_list_init(&b.text_inputs);
    b.socket_name="vessel-0";b.frame_socket="/tmp/vessel-frame-export.sock";b.input_host="10.0.2.2";b.pointer_x=b.width/2.0;b.pointer_y=b.height/2.0;
    static const struct option opts[]={{"socket",required_argument,0,'s'},{"frame-socket",required_argument,0,'f'},{"input-host",required_argument,0,'i'},{"input-port",required_argument,0,'p'},{"width",required_argument,0,'w'},{"height",required_argument,0,'h'},{"refresh",required_argument,0,'r'},{0,0,0,0}};
    int c;while((c=getopt_long(argc,argv,"s:f:i:p:w:h:r:",opts,NULL))!=-1){if(c=='s')b.socket_name=optarg;else if(c=='f')b.frame_socket=optarg;else if(c=='i')b.input_host=optarg;else if(c=='p')b.input_port=atoi(optarg);else if(c=='w')b.width=atoi(optarg);else if(c=='h')b.height=atoi(optarg);else if(c=='r')b.refresh_mhz=atoi(optarg)*1000;}
    b.pointer_x=b.width/2.0;b.pointer_y=b.height/2.0;
    b.display=wl_display_create();if(!b.display)die("wl_display_create");b.loop=wl_display_get_event_loop(b.display);g_bridge=&b;
    b.frame_timer=wl_event_loop_add_timer(b.loop,frame_timer_handler,&b);if(!b.frame_timer)die("frame timer");b.input_timer=wl_event_loop_add_timer(b.loop,input_reconnect,&b);if(!b.input_timer)die("input timer");wl_event_source_timer_update(b.input_timer,1);
    if(init_keymap(&b)<0)die("xkb keymap");if(wl_display_init_shm(b.display)<0)die("wl_display_init_shm");
    if(!wl_global_create(b.display,&wl_compositor_interface,5,&b,bind_compositor))die("wl_compositor");
    if(!wl_global_create(b.display,&wl_subcompositor_interface,1,&b,bind_subcompositor))die("wl_subcompositor");
    if(!wl_global_create(b.display,&wl_data_device_manager_interface,3,&b,bind_data_device_manager))die("wl_data_device_manager");
    if(!wl_global_create(b.display,&wl_seat_interface,7,&b,bind_seat))die("wl_seat");
    if(!wl_global_create(b.display,&wl_output_interface,4,&b,bind_output))die("wl_output");
    if(!wl_global_create(b.display,&xdg_wm_base_interface,3,&b,bind_wm))die("xdg_wm_base");
    if(!wl_global_create(b.display,&zwp_linux_dmabuf_v1_interface,3,&b,bind_dmabuf))die("linux-dmabuf");
    if(!wl_global_create(b.display,&zwp_text_input_manager_v3_interface,1,&b,bind_text_input_manager))die("text-input-v3");
    if(!wl_global_create(b.display,&wp_presentation_interface,1,&b,bind_presentation))die("presentation-time");
    if(!wl_global_create(b.display,&zxdg_decoration_manager_v1_interface,1,&b,bind_deco_mgr))die("xdg-decoration");
    if(wl_display_add_socket(b.display,b.socket_name)<0)die("wl_display_add_socket");
    signal(SIGINT,on_signal);signal(SIGTERM,on_signal);
    fprintf(stderr,"[vessel-compositor] READY v35 socket=%s %dx%d@%dHz dmabuf+SHM-damage seat+IME+clipboard+presentation input=%s:%d\n",b.socket_name,b.width,b.height,b.refresh_mhz/1000,b.input_host,b.input_port);
    wl_display_run(b.display);
    disconnect_frame(&b);if(b.input_source)wl_event_source_remove(b.input_source);if(b.input_fd>=0)close(b.input_fd);if(b.keymap_fd>=0)close(b.keymap_fd);if(b.xkb_state)xkb_state_unref(b.xkb_state);if(b.xkb_keymap)xkb_keymap_unref(b.xkb_keymap);if(b.xkb_context)xkb_context_unref(b.xkb_context);wl_display_destroy_clients(b.display);wl_display_destroy(b.display);return 0;
}
