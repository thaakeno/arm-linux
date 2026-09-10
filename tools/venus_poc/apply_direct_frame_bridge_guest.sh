#!/bin/bash
set -euo pipefail

MESA_VER=26.2.2
SRC="/root/mesa-${MESA_VER}"
TARBALL="/root/mesa-${MESA_VER}.tar.xz"
BUILD="$SRC/build-venus-x11"
STALL_PATCH=/root/mesa-26.2.2-x11-present-stall.patch

echo "[frame-guest] restoring pristine Mesa ${MESA_VER} WSI sources"
for rel in \
  src/vulkan/wsi/wsi_common.c \
  src/vulkan/wsi/wsi_common_private.h \
  src/vulkan/wsi/wsi_common_x11.c \
  src/virtio/vulkan/vn_wsi.c; do
  tar -xJOf "$TARBALL" "mesa-${MESA_VER}/$rel" > "$SRC/$rel"
done

cd "$SRC"
echo "[frame-guest] applying software-present fence deferral"
patch -p1 --batch --forward < "$STALL_PATCH"

python3 - <<'PY'
from pathlib import Path

vn = Path('/root/mesa-26.2.2/src/virtio/vulkan/vn_wsi.c')
s = vn.read_text()
old = '.sw_device = use_sw_device,'
if s.count(old) != 1:
    raise SystemExit(f'expected exactly one {old!r}, found {s.count(old)}')
s = s.replace(old, '.sw_device = true, /* UML direct frame bridge: CPU-backed WSI */', 1)
vn.write_text(s)

x11 = Path('/root/mesa-26.2.2/src/vulkan/wsi/wsi_common_x11.c')
s = x11.read_text()
old = 'wsi_conn->has_mit_shm = x11_xcb_display_supports_xshm(conn, NULL);'
if s.count(old) != 1:
    raise SystemExit(f'expected exactly one MIT-SHM assignment, found {s.count(old)}')
s = s.replace(old, 'wsi_conn->has_mit_shm = false; /* UML guest SHM ids cannot cross Android */', 1)

inc_anchor = '#include <sys/shm.h>\n#endif\n'
inc_add = '#include <sys/shm.h>\n#endif\n#include <arpa/inet.h>\n#include <sys/socket.h>\n'
if inc_anchor not in s:
    raise SystemExit('include anchor not found')
s = s.replace(inc_anchor, inc_add, 1)

start = s.index('static VkResult\nx11_present_to_x11_sw(struct x11_swapchain *chain, uint32_t image_index)\n{')
end = s.index('\nstatic void\nx11_capture_trace', start)
replacement = r'''static int uml_frame_bridge_fd = -1;

static bool
uml_frame_bridge_send_all(int fd, const void *buf, size_t len)
{
   const uint8_t *p = buf;
   while (len) {
      ssize_t n = send(fd, p, len, MSG_NOSIGNAL);
      if (n < 0) {
         if (errno == EINTR)
            continue;
         return false;
      }
      if (n == 0)
         return false;
      p += n;
      len -= (size_t)n;
   }
   return true;
}

static int
uml_frame_bridge_connect(void)
{
   if (uml_frame_bridge_fd >= 0)
      return uml_frame_bridge_fd;

   int fd = socket(AF_INET, SOCK_STREAM, 0);
   if (fd < 0)
      return -1;

   struct sockaddr_in addr = {0};
   addr.sin_family = AF_INET;
   addr.sin_port = htons(6010);
   if (inet_pton(AF_INET, "10.0.2.2", &addr.sin_addr) != 1 ||
       connect(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
      close(fd);
      return -1;
   }

   uml_frame_bridge_fd = fd;
   fprintf(stderr, "UML-FRAME: connected to host 10.0.2.2:6010\n");
   return fd;
}

static VkResult
x11_present_to_x11_sw(struct x11_swapchain *chain, uint32_t image_index)
{
   assert(!chain->base.image_info.explicit_sync);
   struct x11_image *image = &chain->images[image_index];
   const struct wsi_device *wsi = chain->base.wsi;

   /* Keep the real software-blit synchronization.  Vessel's deferral moves
    * this wait off vkQueuePresentKHR; here is the first CPU read of the image.
    */
   if (wsi->sw) {
      VkResult wait = wsi->WaitForFences(chain->base.device, 1,
                                         &chain->base.fences[image_index],
                                         true, ~0ull);
      if (wait != VK_SUCCESS)
         return wait;
   }

   const uint32_t width = chain->extent.width;
   const uint32_t height = chain->extent.height;
   const uint32_t stride = image->base.row_pitches[0];
   const uint32_t size = stride * height;
   uint32_t hdr[5] = {
      htonl(0x554d4652u), /* UMFR */
      htonl(width),
      htonl(height),
      htonl(stride),
      htonl(size),
   };

   int fd = uml_frame_bridge_connect();
   if (fd < 0) {
      fprintf(stderr, "UML-FRAME: host bridge connection failed errno=%d\n", errno);
      return VK_ERROR_SURFACE_LOST_KHR;
   }

   if (!uml_frame_bridge_send_all(fd, hdr, sizeof(hdr)) ||
       !uml_frame_bridge_send_all(fd, image->base.cpu_map, size)) {
      fprintf(stderr, "UML-FRAME: frame send failed errno=%d; reconnect next frame\n", errno);
      close(uml_frame_bridge_fd);
      uml_frame_bridge_fd = -1;
      return VK_ERROR_SURFACE_LOST_KHR;
   }

   static uint64_t frame_no;
   frame_no++;
   if (frame_no <= 5 || frame_no % 60 == 0)
      fprintf(stderr, "UML-FRAME: frame=%" PRIu64 " %ux%u stride=%u size=%u\n",
              frame_no, width, height, stride, size);

   wsi_queue_push(&chain->acquire_queue, image_index);
   return VK_SUCCESS;
}
'''
s = s[:start] + replacement + s[end:]
x11.write_text(s)
print('[frame-guest] replaced remote X11 PutImage with direct host frame stream')
PY

echo "[frame-guest] rebuilding Mesa"
ninja -C "$BUILD" src/virtio/vulkan/libvulkan_virtio.so
ninja -C "$BUILD" install
sync

source /root/venus-env.sh
export DISPLAY=10.0.2.2:0
export VTEST_SOCKET_NAME=/tmp/.venus_test
export VN_DEBUG=vtest
unset VN_PERF || true
export VK_DRIVER_FILES=/root/virtio-wsi-test.json
export XDG_RUNTIME_DIR=/tmp

echo "[frame-guest] device"
vulkaninfo --summary 2>&1 | grep -E 'deviceName|driverName|driverInfo' | head -12 || true

echo "[frame-guest] running vkcube for 20 seconds"
rm -f /tmp/vkcube-frame-bridge.log
set +e
timeout 20s vkcube > /tmp/vkcube-frame-bridge.log 2>&1
rc=$?
set -e
cat /tmp/vkcube-frame-bridge.log

echo "[frame-guest] vkcube exit=$rc"
if [ "$rc" -eq 124 ]; then
  echo "[frame-guest] vkcube survived the full test window"
elif [ "$rc" -ne 0 ]; then
  echo "[frame-guest] vkcube exited early"
fi
