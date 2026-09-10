#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <unistd.h>
#include <xcb/xcb.h>

#define PORT 6017
#define MAGIC 0x554d4652u
#define FRAME_SIZE (16u * 1024u * 1024u)

struct frame_hdr { uint32_t magic, window, width, height, stride, size; };

static int recvn(int fd, void *buf, size_t n) {
    uint8_t *p = buf;
    while (n) {
        ssize_t r = recv(fd, p, n, 0);
        if (r == 0) return 0;
        if (r < 0) { if (errno == EINTR) continue; return -1; }
        p += r; n -= (size_t)r;
    }
    return 1;
}

static uint64_t rgb_hash(const uint8_t *p, uint32_t w, uint32_t h, uint32_t stride) {
    uint64_t x = 1469598103934665603ull;
    for (uint32_t y=0; y<h; y++) {
        const uint8_t *row = p + (size_t)y * stride;
        for (uint32_t i=0; i<w*4u; i++) { x ^= row[i]; x *= 1099511628211ull; }
    }
    return x;
}

static int check_void(xcb_connection_t *c, xcb_void_cookie_t ck, const char *what) {
    xcb_generic_error_t *e = xcb_request_check(c, ck);
    if (!e) return 0;
    fprintf(stderr, "[local-present] X11_ERROR where=%s code=%u major=%u minor=%u\n",
            what, e->error_code, e->major_code, e->minor_code);
    free(e); return -1;
}

static int put_frame(xcb_connection_t *c, xcb_drawable_t win, xcb_gcontext_t gc,
                     uint8_t depth, uint32_t width, uint32_t height,
                     uint32_t stride, const uint8_t *src) {
    const uint32_t packed_stride = width * 4u;
    uint8_t *packed = NULL;
    const uint8_t *pixels = src;
    if (stride != packed_stride) {
        packed = malloc((size_t)packed_stride * height);
        if (!packed) return -1;
        for (uint32_t y=0; y<height; y++)
            memcpy(packed + (size_t)y*packed_stride,
                   src + (size_t)y*stride, packed_stride);
        pixels = packed;
    }
    uint32_t maxb = xcb_get_maximum_request_length(c) * 4u;
    uint32_t overhead = (uint32_t)sizeof(xcb_put_image_request_t) + 64u;
    uint32_t rows = maxb > overhead ? (maxb-overhead)/packed_stride : 1u;
    if (!rows) rows = 1;
    for (uint32_t y=0; y<height; y+=rows) {
        uint32_t n = rows > height-y ? height-y : rows;
        xcb_void_cookie_t ck = xcb_put_image_checked(c, XCB_IMAGE_FORMAT_Z_PIXMAP,
            win, gc, (uint16_t)width, (uint16_t)n, 0, (int16_t)y, 0, depth,
            packed_stride*n, pixels + (size_t)y*packed_stride);
        if (check_void(c, ck, "PutImage(target-window)") < 0) { free(packed); return -1; }
    }
    xcb_flush(c); free(packed); return 0;
}

int main(int argc, char **argv) {
    if (argc != 2) { fprintf(stderr, "usage: %s FRAME_FILE\n", argv[0]); return 2; }
    int ffd = open(argv[1], O_RDONLY);
    if (ffd < 0) { perror("[local-present] open frame"); return 2; }
    uint8_t *shared = mmap(NULL, FRAME_SIZE, PROT_READ, MAP_SHARED, ffd, 0);
    if (shared == MAP_FAILED) { perror("[local-present] mmap"); return 2; }

    int screen_num=0;
    xcb_connection_t *xc = xcb_connect(NULL, &screen_num);
    if (xcb_connection_has_error(xc)) { fprintf(stderr, "[local-present] X11 connect failed\n"); return 2; }

    int s = socket(AF_INET, SOCK_STREAM, 0), one=1;
    setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    struct sockaddr_in a={0}; a.sin_family=AF_INET; a.sin_port=htons(PORT); a.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
    if (bind(s,(struct sockaddr*)&a,sizeof(a))<0 || listen(s,1)<0) { perror("[local-present] listen"); return 2; }
    fprintf(stderr, "[local-present] waiting on 127.0.0.1:%d; will draw into the guest's real X11 window\n", PORT);

    xcb_window_t current=0; xcb_gcontext_t gc=0; uint8_t depth=0;
    unsigned long long frame=0;
    for (;;) {
        int fd=accept(s,NULL,NULL); if (fd<0) continue;
        fprintf(stderr, "[local-present] guest connected\n");
        for (;;) {
            struct frame_hdr h; if (recvn(fd,&h,sizeof(h))<=0) break;
            uint32_t magic=ntohl(h.magic), win=ntohl(h.window), w=ntohl(h.width), hh=ntohl(h.height), stride=ntohl(h.stride), size=ntohl(h.size);
            if (magic!=MAGIC || !win || !w || !hh || stride<w*4u || size!=stride*hh || size>FRAME_SIZE) {
                fprintf(stderr,"[local-present] bad header magic=%08x win=%08x %ux%u stride=%u size=%u\n",magic,win,w,hh,stride,size); break;
            }
            if (current != win) {
                if (gc) xcb_free_gc(xc,gc);
                xcb_get_geometry_cookie_t gck=xcb_get_geometry(xc,win);
                xcb_generic_error_t *err=NULL; xcb_get_geometry_reply_t *gr=xcb_get_geometry_reply(xc,gck,&err);
                if (!gr) { fprintf(stderr,"[local-present] target window %08x not found\n",win); free(err); break; }
                depth=gr->depth; free(gr); free(err);
                current=win; gc=xcb_generate_id(xc);
                if (check_void(xc,xcb_create_gc_checked(xc,gc,current,0,NULL),"CreateGC(target-window)")<0) break;
                fprintf(stderr,"[local-present] TARGET window=%08x depth=%u\n",current,depth);
            }
            uint64_t src=rgb_hash(shared,w,hh,stride);
            if (put_frame(xc,current,gc,depth,w,hh,stride,shared)<0) break;
            frame++;
            if (frame<=8 || frame%30==0) fprintf(stderr,"[local-present] frame=%llu target=%08x hash=%016llx\n",frame,current,(unsigned long long)src);
        }
        close(fd);
        fprintf(stderr,"[local-present] guest disconnected after %llu frames\n",frame);
        if (frame) break;
    }
    if (frame) fprintf(stderr,"[local-present] DIAG=DIRECT_TO_REAL_WINDOW_WORKS frames=%llu\n",frame);
    else fprintf(stderr,"[local-present] DIAG=NO_DIRECT_FRAMES\n");
    if (gc) xcb_free_gc(xc,gc);
    close(s); xcb_disconnect(xc); munmap(shared,FRAME_SIZE); close(ffd); return 0;
}
