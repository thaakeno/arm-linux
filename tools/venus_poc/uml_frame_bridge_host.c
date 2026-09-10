#include <arpa/inet.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>
#include <xcb/xcb.h>

#define PORT 6010
#define MAGIC 0x554d4652u /* UMFR */

struct frame_hdr {
    uint32_t magic;
    uint32_t width;
    uint32_t height;
    uint32_t stride;
    uint32_t size;
};

static int recvn(int fd, void *buf, size_t n)
{
    uint8_t *p = buf;
    while (n) {
        ssize_t r = recv(fd, p, n, 0);
        if (r == 0) return 0;
        if (r < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        p += r;
        n -= (size_t)r;
    }
    return 1;
}

static xcb_screen_t *screen_for(xcb_connection_t *c, int screen_num)
{
    const xcb_setup_t *setup = xcb_get_setup(c);
    xcb_screen_iterator_t it = xcb_setup_roots_iterator(setup);
    while (screen_num-- > 0) xcb_screen_next(&it);
    return it.data;
}

static int present_frame(xcb_connection_t *c, xcb_window_t win, xcb_gcontext_t gc,
                         uint8_t depth, uint32_t width, uint32_t height,
                         uint32_t stride, const uint8_t *src)
{
    const uint32_t packed_stride = width * 4u;
    uint8_t *packed = NULL;
    const uint8_t *pixels = src;

    if (stride != packed_stride) {
        packed = malloc((size_t)packed_stride * height);
        if (!packed) return -1;
        for (uint32_t y = 0; y < height; y++)
            memcpy(packed + (size_t)y * packed_stride,
                   src + (size_t)y * stride,
                   packed_stride);
        pixels = packed;
    }

    const uint32_t max_req_bytes = xcb_get_maximum_request_length(c) * 4u;
    const uint32_t overhead = (uint32_t)sizeof(xcb_put_image_request_t) + 64u;
    uint32_t rows = (max_req_bytes > overhead) ?
                    (max_req_bytes - overhead) / packed_stride : 1u;
    if (rows == 0) rows = 1;

    for (uint32_t y = 0; y < height; y += rows) {
        uint32_t nrows = rows;
        if (nrows > height - y) nrows = height - y;
        xcb_put_image(c, XCB_IMAGE_FORMAT_Z_PIXMAP, win, gc,
                      (uint16_t)width, (uint16_t)nrows,
                      0, (int16_t)y, 0, depth,
                      packed_stride * nrows,
                      pixels + (size_t)y * packed_stride);
    }
    xcb_flush(c);
    free(packed);
    return 0;
}

int main(void)
{
    int screen_num = 0;
    xcb_connection_t *xc = xcb_connect(NULL, &screen_num);
    if (xcb_connection_has_error(xc)) {
        fprintf(stderr, "[frame-host] cannot connect to local Termux:X11 DISPLAY=%s\n",
                getenv("DISPLAY") ? getenv("DISPLAY") : "(unset)");
        return 2;
    }

    xcb_screen_t *scr = screen_for(xc, screen_num);
    if (!scr) return 2;

    xcb_window_t win = xcb_generate_id(xc);
    uint32_t values[] = { scr->black_pixel, XCB_EVENT_MASK_EXPOSURE };
    xcb_create_window(xc, scr->root_depth, win, scr->root,
                      0, 0, 640, 480, 0,
                      XCB_WINDOW_CLASS_INPUT_OUTPUT, scr->root_visual,
                      XCB_CW_BACK_PIXEL | XCB_CW_EVENT_MASK, values);
    xcb_change_property(xc, XCB_PROP_MODE_REPLACE, win,
                        XCB_ATOM_WM_NAME, XCB_ATOM_STRING, 8,
                        24, "UML Vulkan Frame Bridge");
    xcb_gcontext_t gc = xcb_generate_id(xc);
    xcb_create_gc(xc, gc, win, 0, NULL);
    xcb_map_window(xc, win);
    xcb_flush(xc);

    int s = socket(AF_INET, SOCK_STREAM, 0);
    int one = 1;
    setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    struct sockaddr_in a = {0};
    a.sin_family = AF_INET;
    a.sin_port = htons(PORT);
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(s, (struct sockaddr *)&a, sizeof(a)) < 0 || listen(s, 1) < 0) {
        perror("[frame-host] listen");
        return 2;
    }

    fprintf(stderr, "[frame-host] listening 127.0.0.1:%d and drawing to local Termux:X11\n", PORT);
    fflush(stderr);

    unsigned long long frame = 0;
    for (;;) {
        int fd = accept(s, NULL, NULL);
        if (fd < 0) {
            if (errno == EINTR) continue;
            perror("[frame-host] accept");
            break;
        }
        fprintf(stderr, "[frame-host] guest connected\n");
        fflush(stderr);

        for (;;) {
            struct frame_hdr h;
            int rr = recvn(fd, &h, sizeof(h));
            if (rr <= 0) break;
            uint32_t magic = ntohl(h.magic);
            uint32_t width = ntohl(h.width);
            uint32_t height = ntohl(h.height);
            uint32_t stride = ntohl(h.stride);
            uint32_t size = ntohl(h.size);
            if (magic != MAGIC || width == 0 || height == 0 ||
                stride < width * 4u || size != stride * height ||
                size > 64u * 1024u * 1024u) {
                fprintf(stderr, "[frame-host] invalid frame header magic=%08x %ux%u stride=%u size=%u\n",
                        magic, width, height, stride, size);
                break;
            }

            uint8_t *buf = malloc(size);
            if (!buf) break;
            rr = recvn(fd, buf, size);
            if (rr <= 0) { free(buf); break; }

            uint32_t cfg[2] = { width, height };
            xcb_configure_window(xc, win,
                                 XCB_CONFIG_WINDOW_WIDTH | XCB_CONFIG_WINDOW_HEIGHT,
                                 cfg);
            if (present_frame(xc, win, gc, scr->root_depth,
                              width, height, stride, buf) < 0) {
                free(buf);
                break;
            }
            free(buf);
            frame++;
            if (frame <= 5 || frame % 60 == 0) {
                fprintf(stderr, "[frame-host] frame=%llu %ux%u stride=%u\n",
                        frame, width, height, stride);
                fflush(stderr);
            }
        }
        close(fd);
        fprintf(stderr, "[frame-host] guest disconnected after %llu frames\n", frame);
        fflush(stderr);
    }

    close(s);
    xcb_disconnect(xc);
    return 0;
}
