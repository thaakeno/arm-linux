#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>
#include <xcb/xcb.h>

#define PORT 6017
#define MAGIC 0x554d4652u
#define FRAME_SIZE (16u * 1024u * 1024u)

struct frame_hdr {
    uint32_t magic, width, height, stride, size;
};

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

static uint64_t fnv1a64(const uint8_t *p, size_t n) {
    uint64_t h = 1469598103934665603ull;
    for (size_t i = 0; i < n; i++) { h ^= p[i]; h *= 1099511628211ull; }
    return h;
}

static uint64_t rgb_hash(const uint8_t *p, uint32_t width, uint32_t height, uint32_t stride) {
    uint64_t h = 1469598103934665603ull;
    for (uint32_t y = 0; y < height; y++) {
        const uint8_t *row = p + (size_t)y * stride;
        for (uint32_t x = 0; x < width; x++) {
            const uint8_t *px = row + x * 4u;
            for (int c = 0; c < 3; c++) { h ^= px[c]; h *= 1099511628211ull; }
        }
    }
    return h;
}

static xcb_screen_t *screen_for(xcb_connection_t *c, int screen_num) {
    const xcb_setup_t *setup = xcb_get_setup(c);
    xcb_screen_iterator_t it = xcb_setup_roots_iterator(setup);
    while (screen_num-- > 0) xcb_screen_next(&it);
    return it.data;
}

static int check_void(xcb_connection_t *c, xcb_void_cookie_t ck, const char *what) {
    xcb_generic_error_t *e = xcb_request_check(c, ck);
    if (!e) return 0;
    fprintf(stderr, "[forensic] X11_ERROR where=%s code=%u major=%u minor=%u seq=%u\n",
            what, e->error_code, e->major_code, e->minor_code, e->sequence);
    free(e);
    return -1;
}

static int put_frame(xcb_connection_t *c, xcb_window_t win, xcb_gcontext_t gc,
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

    const uint32_t max_req_bytes = xcb_get_maximum_request_length(c) * 4u;
    const uint32_t overhead = (uint32_t)sizeof(xcb_put_image_request_t) + 64u;
    uint32_t rows = max_req_bytes > overhead ? (max_req_bytes - overhead) / packed_stride : 1u;
    if (!rows) rows = 1;

    int bad = 0;
    for (uint32_t y = 0; y < height; y += rows) {
        uint32_t nrows = rows > height - y ? height - y : rows;
        if (checked) {
            xcb_void_cookie_t ck = xcb_put_image_checked(
                c, XCB_IMAGE_FORMAT_Z_PIXMAP, win, gc,
                (uint16_t)width, (uint16_t)nrows, 0, (int16_t)y,
                0, depth, packed_stride * nrows,
                pixels + (size_t)y * packed_stride);
            if (check_void(c, ck, "PutImage") < 0) bad = 1;
        } else {
            xcb_put_image(c, XCB_IMAGE_FORMAT_Z_PIXMAP, win, gc,
                          (uint16_t)width, (uint16_t)nrows, 0, (int16_t)y,
                          0, depth, packed_stride * nrows,
                          pixels + (size_t)y * packed_stride);
        }
    }
    xcb_flush(c);
    free(packed);
    return bad ? -1 : 0;
}

static int readback_rgb_hash(xcb_connection_t *c, xcb_drawable_t win,
                             uint32_t width, uint32_t height,
                             uint64_t *out_hash, uint8_t center[4]) {
    xcb_get_image_cookie_t ck = xcb_get_image(c, XCB_IMAGE_FORMAT_Z_PIXMAP,
                                              win, 0, 0, width, height, 0xffffffffu);
    xcb_generic_error_t *err = NULL;
    xcb_get_image_reply_t *r = xcb_get_image_reply(c, ck, &err);
    if (err) {
        fprintf(stderr, "[forensic] X11_ERROR where=GetImage code=%u major=%u minor=%u\n",
                err->error_code, err->major_code, err->minor_code);
        free(err);
    }
    if (!r) return -1;
    int len = xcb_get_image_data_length(r);
    uint8_t *data = xcb_get_image_data(r);
    size_t need = (size_t)width * height * 4u;
    if ((size_t)len < need) {
        fprintf(stderr, "[forensic] READBACK_SHORT got=%d need=%zu depth=%u\n", len, need, r->depth);
        free(r);
        return -1;
    }
    *out_hash = rgb_hash(data, width, height, width * 4u);
    const uint8_t *p = data + ((size_t)(height / 2) * width + width / 2) * 4u;
    memcpy(center, p, 4);
    free(r);
    return 0;
}

static uint8_t *make_test_pattern(uint32_t w, uint32_t h) {
    uint8_t *p = calloc((size_t)w * h, 4);
    if (!p) return NULL;
    for (uint32_t y = 0; y < h; y++) for (uint32_t x = 0; x < w; x++) {
        uint8_t *q = p + ((size_t)y * w + x) * 4u;
        if (x < w/2 && y < h/2)      { q[0]=0;   q[1]=0;   q[2]=255; q[3]=255; }
        else if (x >= w/2 && y < h/2){ q[0]=0;   q[1]=255; q[2]=0;   q[3]=255; }
        else if (x < w/2)            { q[0]=255; q[1]=0;   q[2]=0;   q[3]=255; }
        else                         { q[0]=255; q[1]=255; q[2]=255; q[3]=255; }
    }
    return p;
}

int main(int argc, char **argv) {
    if (argc != 2) { fprintf(stderr, "usage: %s FRAME_FILE\n", argv[0]); return 2; }
    int ffd = open(argv[1], O_RDONLY);
    if (ffd < 0) { perror("[frame-host] open shared frame"); return 2; }
    uint8_t *shared = mmap(NULL, FRAME_SIZE, PROT_READ, MAP_SHARED, ffd, 0);
    if (shared == MAP_FAILED) { perror("[frame-host] mmap shared frame"); return 2; }

    int screen_num = 0;
    xcb_connection_t *xc = xcb_connect(NULL, &screen_num);
    if (xcb_connection_has_error(xc)) { fprintf(stderr, "[forensic] DIAG=LOCAL_X11_CONNECT_FAILED\n"); return 2; }
    xcb_screen_t *scr = screen_for(xc, screen_num);
    if (!scr) return 2;
    fprintf(stderr, "[forensic] X11_SETUP depth=%u image_byte_order=%u bitmap_bit_order=%u max_req=%u\n",
            scr->root_depth, xcb_get_setup(xc)->image_byte_order,
            xcb_get_setup(xc)->bitmap_format_bit_order,
            xcb_get_maximum_request_length(xc));

    xcb_window_t win = xcb_generate_id(xc);
    uint32_t values[] = { scr->black_pixel, XCB_EVENT_MASK_EXPOSURE };
    if (check_void(xc, xcb_create_window_checked(xc, scr->root_depth, win, scr->root,
                      0, 0, 500, 500, 0, XCB_WINDOW_CLASS_INPUT_OUTPUT, scr->root_visual,
                      XCB_CW_BACK_PIXEL | XCB_CW_EVENT_MASK, values), "CreateWindow") < 0) return 2;
    const char *title = "UML Vulkan Frame Forensics";
    check_void(xc, xcb_change_property_checked(xc, XCB_PROP_MODE_REPLACE, win,
               XCB_ATOM_WM_NAME, XCB_ATOM_STRING, 8, strlen(title), title), "WM_NAME");
    xcb_gcontext_t gc = xcb_generate_id(xc);
    check_void(xc, xcb_create_gc_checked(xc, gc, win, 0, NULL), "CreateGC");
    if (check_void(xc, xcb_map_window_checked(xc, win), "MapWindow") < 0) return 2;
    xcb_flush(xc);

    uint8_t *pattern = make_test_pattern(500, 500);
    uint64_t pattern_src = rgb_hash(pattern, 500, 500, 2000);
    int synth_put = put_frame(xc, win, gc, scr->root_depth, 500, 500, 2000, pattern, 1);
    uint64_t pattern_rb = 0; uint8_t pattern_center[4] = {0};
    int synth_rb = readback_rgb_hash(xc, win, 500, 500, &pattern_rb, pattern_center);
    fprintf(stderr, "[forensic] SYNTH src_rgb=%016llx readback_rgb=%016llx center=%u,%u,%u,%u put=%s readback=%s\n",
            (unsigned long long)pattern_src, (unsigned long long)pattern_rb,
            pattern_center[0],pattern_center[1],pattern_center[2],pattern_center[3],
            synth_put==0?"OK":"FAIL", synth_rb==0?"OK":"FAIL");
    int synth_ok = synth_put == 0 && synth_rb == 0 && pattern_src == pattern_rb;
    fprintf(stderr, "[forensic] SYNTH_X11=%s\n", synth_ok ? "PASS" : "FAIL");
    free(pattern);

    int s = socket(AF_INET, SOCK_STREAM, 0), one = 1;
    setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    struct sockaddr_in a = {0}; a.sin_family = AF_INET; a.sin_port = htons(PORT); a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(s, (struct sockaddr *)&a, sizeof(a)) < 0 || listen(s, 1) < 0) { perror("[frame-host] listen"); return 2; }
    fprintf(stderr, "[frame-host] shared frame mapped; listening 127.0.0.1:%d\n", PORT); fflush(stderr);

    unsigned long long frame = 0;
    uint64_t first_rgb = 0, last_rgb = 0;
    unsigned distinct_changes = 0;
    int first_upload_ok = 0, first_readback_ok = 0, first_match = 0;

    for (;;) {
        int fd = accept(s, NULL, NULL);
        if (fd < 0) continue;
        fprintf(stderr, "[frame-host] guest connected\n"); fflush(stderr);
        for (;;) {
            struct frame_hdr h;
            int rr = recvn(fd, &h, sizeof(h)); if (rr <= 0) break;
            uint32_t magic=ntohl(h.magic), width=ntohl(h.width), height=ntohl(h.height), stride=ntohl(h.stride), size=ntohl(h.size);
            if (magic != MAGIC || !width || !height || stride < width*4u || size != stride*height || size > FRAME_SIZE) {
                fprintf(stderr, "[forensic] DIAG=BAD_FRAME_HEADER magic=%08x %ux%u stride=%u size=%u\n", magic,width,height,stride,size); break;
            }

            uint64_t full = fnv1a64(shared, size);
            uint64_t rgb = rgb_hash(shared, width, height, stride);
            const uint8_t *cp = shared + (size_t)(height/2) * stride + (width/2) * 4u;
            if (frame == 0) first_rgb = rgb;
            if (frame > 0 && rgb != last_rgb) distinct_changes++;
            last_rgb = rgb;
            if (frame < 8 || frame % 20 == 0)
                fprintf(stderr, "[forensic] SOURCE frame=%llu full=%016llx rgb=%016llx center=%u,%u,%u,%u\n",
                        frame+1, (unsigned long long)full, (unsigned long long)rgb,
                        cp[0],cp[1],cp[2],cp[3]);

            uint32_t cfg[2] = { width, height };
            check_void(xc, xcb_configure_window_checked(xc, win,
                       XCB_CONFIG_WINDOW_WIDTH | XCB_CONFIG_WINDOW_HEIGHT, cfg), "ConfigureWindow");
            int upload = put_frame(xc, win, gc, scr->root_depth, width, height, stride, shared, frame == 0);
            if (frame == 0) {
                first_upload_ok = upload == 0;
                uint64_t rb = 0; uint8_t center[4] = {0};
                if (readback_rgb_hash(xc, win, width, height, &rb, center) == 0) {
                    first_readback_ok = 1;
                    first_match = (rb == rgb);
                    fprintf(stderr, "[forensic] FIRST_READBACK source_rgb=%016llx x11_rgb=%016llx match=%s center=%u,%u,%u,%u\n",
                            (unsigned long long)rgb, (unsigned long long)rb,
                            first_match?"YES":"NO", center[0],center[1],center[2],center[3]);
                }
            }
            frame++;
        }
        close(fd);
        fprintf(stderr, "[frame-host] guest disconnected after %llu frames\n", frame); fflush(stderr);
        if (frame) break;
    }

    fprintf(stderr, "[forensic] SUMMARY frames=%llu source_changes=%u synth_x11=%s first_upload=%s first_readback=%s first_match=%s first_rgb=%016llx last_rgb=%016llx\n",
            frame, distinct_changes, synth_ok?"PASS":"FAIL", first_upload_ok?"PASS":"FAIL",
            first_readback_ok?"PASS":"FAIL", first_match?"YES":"NO",
            (unsigned long long)first_rgb, (unsigned long long)last_rgb);

    if (!synth_ok)
        fprintf(stderr, "[forensic] DIAG=LOCAL_TERMUX_X11_PIXEL_PATH_BROKEN\n");
    else if (frame == 0)
        fprintf(stderr, "[forensic] DIAG=NO_WSI_FRAMES\n");
    else if (distinct_changes == 0)
        fprintf(stderr, "[forensic] DIAG=VENUS_WSI_IMAGE_STATIC_OR_STALE\n");
    else if (!first_upload_ok || !first_readback_ok || !first_match)
        fprintf(stderr, "[forensic] DIAG=LOCAL_X11_UPLOAD_OR_PIXEL_FORMAT_MISMATCH\n");
    else
        fprintf(stderr, "[forensic] DIAG=PIXELS_DYNAMIC_AND_X11_READBACK_MATCHES\n");

    close(s); munmap(shared, FRAME_SIZE); close(ffd); xcb_disconnect(xc);
    return 0;
}
