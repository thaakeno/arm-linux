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
    for (uint32_t y = 0; y < h; y++) {
        const uint8_t *row = p + (size_t)y * stride;
        for (uint32_t i = 0; i < w * 4u; i++) { x ^= row[i]; x *= 1099511628211ull; }
    }
    return x;
}

static int check_void(xcb_connection_t *c, xcb_void_cookie_t ck, const char *what) {
    xcb_generic_error_t *e = xcb_request_check(c, ck);
    if (!e) return 0;
    fprintf(stderr, "[local-present] X11_ERROR where=%s code=%u major=%u minor=%u\n",
            what, e->error_code, e->major_code, e->minor_code);
    free(e);
    return -1;
}

static xcb_screen_t *screen_for(xcb_connection_t *c, int screen_num) {
    const xcb_setup_t *setup = xcb_get_setup(c);
    xcb_screen_iterator_t it = xcb_setup_roots_iterator(setup);
    while (screen_num-- > 0) xcb_screen_next(&it);
    return it.data;
}

static int put_frame(xcb_connection_t *c, xcb_drawable_t win, xcb_gcontext_t gc,
                     uint8_t depth, uint32_t width, uint32_t height,
                     uint32_t stride, const uint8_t *src, int checked) {
    const uint32_t packed_stride = width * 4u;
    uint8_t *packed = NULL;
    const uint8_t *pixels = src;
    if (stride != packed_stride) {
        packed = malloc((size_t)packed_stride * height);
        if (!packed) return -1;
        for (uint32_t y = 0; y < height; y++)
            memcpy(packed + (size_t)y * packed_stride,
                   src + (size_t)y * stride, packed_stride);
        pixels = packed;
    }

    uint32_t maxb = xcb_get_maximum_request_length(c) * 4u;
    uint32_t overhead = (uint32_t)sizeof(xcb_put_image_request_t) + 64u;
    uint32_t rows = maxb > overhead ? (maxb - overhead) / packed_stride : 1u;
    if (!rows) rows = 1;

    for (uint32_t y = 0; y < height; y += rows) {
        uint32_t n = rows > height - y ? height - y : rows;
        if (checked) {
            xcb_void_cookie_t ck = xcb_put_image_checked(
                c, XCB_IMAGE_FORMAT_Z_PIXMAP, win, gc,
                (uint16_t)width, (uint16_t)n, 0, (int16_t)y, 0, depth,
                packed_stride * n, pixels + (size_t)y * packed_stride);
            if (check_void(c, ck, "PutImage(proxy)") < 0) { free(packed); return -1; }
        } else {
            xcb_put_image(c, XCB_IMAGE_FORMAT_Z_PIXMAP, win, gc,
                          (uint16_t)width, (uint16_t)n, 0, (int16_t)y, 0, depth,
                          packed_stride * n, pixels + (size_t)y * packed_stride);
        }
    }
    xcb_flush(c);
    free(packed);
    return 0;
}

static int verify_proxy_readback(xcb_connection_t *c, xcb_window_t win,
                                 const uint8_t *src, uint32_t w, uint32_t h,
                                 uint32_t stride) {
    xcb_get_image_cookie_t ck = xcb_get_image(c, XCB_IMAGE_FORMAT_Z_PIXMAP,
                                              win, 0, 0, w, h, 0xffffffffu);
    xcb_generic_error_t *err = NULL;
    xcb_get_image_reply_t *r = xcb_get_image_reply(c, ck, &err);
    if (err) {
        fprintf(stderr, "[local-present] X11_ERROR where=GetImage code=%u major=%u minor=%u\n",
                err->error_code, err->major_code, err->minor_code);
        free(err);
    }
    if (!r) return -1;
    int len = xcb_get_image_data_length(r);
    size_t need = (size_t)w * h * 4u;
    if (len < 0 || (size_t)len < need) { free(r); return -1; }
    uint64_t src_h = rgb_hash(src, w, h, stride);
    uint64_t dst_h = rgb_hash(xcb_get_image_data(r), w, h, w * 4u);
    free(r);
    fprintf(stderr, "[local-present] VISIBLE_VERIFY source=%016llx x11=%016llx match=%s\n",
            (unsigned long long)src_h, (unsigned long long)dst_h,
            src_h == dst_h ? "YES" : "NO");
    return src_h == dst_h ? 0 : -1;
}

static int target_geometry(xcb_connection_t *c, xcb_screen_t *scr, xcb_window_t target,
                           int16_t *x, int16_t *y, uint16_t *w, uint16_t *h, uint8_t *depth) {
    xcb_generic_error_t *err = NULL;
    xcb_get_geometry_reply_t *gr = xcb_get_geometry_reply(c, xcb_get_geometry(c, target), &err);
    if (!gr) { free(err); return -1; }
    *w = gr->width; *h = gr->height; *depth = gr->depth;
    free(gr); free(err);

    err = NULL;
    xcb_translate_coordinates_reply_t *tr = xcb_translate_coordinates_reply(
        c, xcb_translate_coordinates(c, target, scr->root, 0, 0), &err);
    if (!tr) { free(err); return -1; }
    *x = tr->dst_x; *y = tr->dst_y;
    free(tr); free(err);
    return 0;
}

int main(int argc, char **argv) {
    if (argc != 2) { fprintf(stderr, "usage: %s FRAME_FILE\n", argv[0]); return 2; }
    int ffd = open(argv[1], O_RDONLY);
    if (ffd < 0) { perror("[local-present] open frame"); return 2; }
    uint8_t *shared = mmap(NULL, FRAME_SIZE, PROT_READ, MAP_SHARED, ffd, 0);
    if (shared == MAP_FAILED) { perror("[local-present] mmap"); return 2; }

    int screen_num = 0;
    xcb_connection_t *xc = xcb_connect(NULL, &screen_num);
    if (xcb_connection_has_error(xc)) { fprintf(stderr, "[local-present] X11 connect failed\n"); return 2; }
    xcb_screen_t *scr = screen_for(xc, screen_num);
    if (!scr) return 2;

    int s = socket(AF_INET, SOCK_STREAM, 0), one = 1;
    setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    struct sockaddr_in a = {0};
    a.sin_family = AF_INET; a.sin_port = htons(PORT); a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(s, (struct sockaddr *)&a, sizeof(a)) < 0 || listen(s, 1) < 0) {
        perror("[local-present] listen"); return 2;
    }
    fprintf(stderr, "[local-present] waiting on 127.0.0.1:%d; local proxy will stay above the guest window\n", PORT);

    xcb_window_t target = 0, proxy = 0;
    xcb_gcontext_t gc = 0;
    uint8_t target_depth = 0;
    unsigned long long frame = 0;
    int verified = 0;

    int fd = accept(s, NULL, NULL);
    if (fd < 0) return 2;
    fprintf(stderr, "[local-present] guest connected\n");

    for (;;) {
        struct frame_hdr h;
        if (recvn(fd, &h, sizeof(h)) <= 0) break;
        uint32_t magic = ntohl(h.magic), win = ntohl(h.window), w = ntohl(h.width),
                 hh = ntohl(h.height), stride = ntohl(h.stride), size = ntohl(h.size);
        if (magic != MAGIC || !win || !w || !hh || stride < w * 4u || size != stride * hh || size > FRAME_SIZE) {
            fprintf(stderr, "[local-present] bad header magic=%08x win=%08x %ux%u stride=%u size=%u\n",
                    magic, win, w, hh, stride, size);
            break;
        }

        int16_t px = 0, py = 0;
        uint16_t pw = (uint16_t)w, ph = (uint16_t)hh;
        if (!proxy || target != win || frame % 30 == 0) {
            if (target_geometry(xc, scr, win, &px, &py, &pw, &ph, &target_depth) < 0) {
                fprintf(stderr, "[local-present] target window %08x not found\n", win);
                break;
            }
        }

        if (!proxy || target != win) {
            if (proxy) {
                xcb_destroy_window(xc, proxy);
                proxy = 0; gc = 0;
            }
            target = win;
            if (target_depth != scr->root_depth) {
                fprintf(stderr, "[local-present] unsupported target depth=%u root_depth=%u\n",
                        target_depth, scr->root_depth);
                break;
            }
            proxy = xcb_generate_id(xc);
            uint32_t vals[] = { scr->black_pixel, 1u, XCB_EVENT_MASK_EXPOSURE };
            uint32_t mask = XCB_CW_BACK_PIXEL | XCB_CW_OVERRIDE_REDIRECT | XCB_CW_EVENT_MASK;
            if (check_void(xc, xcb_create_window_checked(
                    xc, scr->root_depth, proxy, scr->root,
                    px, py, pw, ph, 0, XCB_WINDOW_CLASS_INPUT_OUTPUT,
                    scr->root_visual, mask, vals), "CreateWindow(proxy)") < 0) break;
            const char *title = "UML Vulkan Local Presenter";
            xcb_change_property(xc, XCB_PROP_MODE_REPLACE, proxy,
                                XCB_ATOM_WM_NAME, XCB_ATOM_STRING, 8,
                                (uint32_t)strlen(title), title);
            gc = xcb_generate_id(xc);
            if (check_void(xc, xcb_create_gc_checked(xc, gc, proxy, 0, NULL), "CreateGC(proxy)") < 0) break;
            if (check_void(xc, xcb_map_window_checked(xc, proxy), "MapWindow(proxy)") < 0) break;
            fprintf(stderr, "[local-present] PROXY target=%08x proxy=%08x pos=%d,%d size=%ux%u depth=%u\n",
                    target, proxy, px, py, pw, ph, target_depth);
        }

        uint32_t cfg[5] = { (uint32_t)(int32_t)px, (uint32_t)(int32_t)py,
                            w, hh, XCB_STACK_MODE_ABOVE };
        uint16_t cfgmask = XCB_CONFIG_WINDOW_X | XCB_CONFIG_WINDOW_Y |
                           XCB_CONFIG_WINDOW_WIDTH | XCB_CONFIG_WINDOW_HEIGHT |
                           XCB_CONFIG_WINDOW_STACK_MODE;
        xcb_configure_window(xc, proxy, cfgmask, cfg);

        int first = frame == 0;
        if (put_frame(xc, proxy, gc, scr->root_depth, w, hh, stride, shared, first) < 0) break;
        if (first) {
            verified = verify_proxy_readback(xc, proxy, shared, w, hh, stride) == 0;
            fprintf(stderr, "[local-present] VISIBLE_VERIFY=%s\n", verified ? "PASS" : "FAIL");
        }
        frame++;
        if (frame <= 5 || frame % 30 == 0)
            fprintf(stderr, "[local-present] frame=%llu proxy=%08x target=%08x\n", frame, proxy, target);
    }

    close(fd);
    fprintf(stderr, "[local-present] guest disconnected after %llu frames\n", frame);
    if (frame && verified)
        fprintf(stderr, "[local-present] DIAG=VISIBLE_PROXY_PRESENT_WORKS frames=%llu\n", frame);
    else if (frame)
        fprintf(stderr, "[local-present] DIAG=PROXY_PIXELS_DID_NOT_VERIFY frames=%llu\n", frame);
    else
        fprintf(stderr, "[local-present] DIAG=NO_DIRECT_FRAMES\n");

    /* Keep the final rendered frame on screen briefly after vkcube exits. */
    if (proxy && frame) {
        xcb_configure_window(xc, proxy, XCB_CONFIG_WINDOW_STACK_MODE,
                             (uint32_t[]){ XCB_STACK_MODE_ABOVE });
        xcb_flush(xc);
        sleep(4);
    }

    if (gc) xcb_free_gc(xc, gc);
    if (proxy) xcb_destroy_window(xc, proxy);
    close(s); xcb_disconnect(xc); munmap(shared, FRAME_SIZE); close(ffd);
    return 0;
}
