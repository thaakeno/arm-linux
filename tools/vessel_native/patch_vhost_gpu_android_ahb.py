#!/usr/bin/env python3
from __future__ import annotations

import pathlib
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_vhost_gpu_android_ahb.py <vhost-device source dir>")

root = pathlib.Path(sys.argv[1])
path = root / "vhost-device-gpu/src/backend/virgl.rs"
text = path.read_text()
marker = "VESSEL_ANDROID_AHB_SCANOUT_V1"
if marker in text:
    print("[vhost-gpu-patch] Android HardwareBuffer scanout patch already applied")
    raise SystemExit(0)

const_anchor = "const CAPSET_ID_VENUS: u32 = 4;\n"
ffi = r'''

// VESSEL_ANDROID_AHB_SCANOUT_V1
// Android/ANGLE does not guarantee EGL_MESA_image_dma_buf_export.  Vessel
// therefore keeps VirGL rendering on the GPU and copies the scanout texture
// into an Android HardwareBuffer in the same process.  The AHardwareBuffer
// handle is then shared with the Android presenter over a same-UID AF_UNIX
// socket.  No CPU readback or RGB transport is involved.
extern "C" {
    fn vessel_ahb_set_scanout(
        resource_id: u32,
        scanout_id: u32,
        x: u32,
        y: u32,
        width: u32,
        height: u32,
    ) -> libc::c_int;
    fn vessel_ahb_update(resource_id: u32, scanout_id: u32) -> libc::c_int;
    fn vessel_ahb_disable(scanout_id: u32) -> libc::c_int;
}
'''
if text.count(const_anchor) != 1:
    raise SystemExit("unexpected virgl capset constants")
text = text.replace(const_anchor, const_anchor + ffi, 1)

# Match function-name anchors rather than a particular rustfmt signature layout.
# In the pinned vhost-device revision set_scanout is multiline while
# flush_resource is intentionally kept on one line.
set_anchor = "    fn set_scanout("
flush_anchor = "    fn flush_resource("
blob_anchor = "    fn resource_create_blob("
if text.count(set_anchor) != 1 or text.count(flush_anchor) != 1 or text.count(blob_anchor) != 1:
    raise SystemExit("unexpected virgl Renderer method layout")

set_start = text.index(set_anchor)
flush_start = text.index(flush_anchor, set_start)
new_set = r'''    fn set_scanout(
        &mut self,
        scanout_id: u32,
        resource_id: u32,
        rect: virtio_gpu_rect,
    ) -> VirtioGpuResult {
        if self.gpu_backend.is_none() {
            return Ok(OkNoData);
        }

        let scanout_idx = scanout_id as usize;
        if scanout_idx >= VIRTIO_GPU_MAX_SCANOUTS as usize {
            return Err(ErrInvalidScanoutId);
        }

        let current_scanout_resource_id =
            self.scanouts[scanout_idx].as_ref().map(|s| s.resource_id);
        if let Some(old_resource_id) = current_scanout_resource_id {
            if old_resource_id != resource_id {
                if let Some(old_resource) = self.resources.get_mut(&old_resource_id) {
                    old_resource.scanouts.disable(scanout_id);
                }
            }
        }

        if resource_id == 0 {
            common_set_scanout_disable(&mut self.scanouts, scanout_idx);
            // SAFETY: vessel_ahb_disable is a process-local C ABI function linked
            // into this binary. It only consumes the validated scanout index.
            let rc = unsafe { vessel_ahb_disable(scanout_id) };
            if rc != 0 {
                error!("Vessel AHardwareBuffer scanout disable failed: rc={rc}");
                return Err(ErrUnspec);
            }
            return Ok(OkNoData);
        }

        let resource = self
            .resources
            .get_mut(&resource_id)
            .ok_or(ErrInvalidResourceId)?;

        // Ensure virglrenderer context 0 is current before the bridge accesses
        // the resource's GL texture through virgl_renderer_resource_get_info.
        self.renderer.force_ctx_0();
        // SAFETY: all arguments are validated scalar values. The bridge is
        // linked into this process and accesses the resource through the public
        // virglrenderer C API while context 0 is current.
        let rc = unsafe {
            vessel_ahb_set_scanout(
                resource_id,
                scanout_id,
                rect.x.into(),
                rect.y.into(),
                rect.width.into(),
                rect.height.into(),
            )
        };
        if rc != 0 {
            error!(
                "Vessel Android HardwareBuffer scanout failed for resource {resource_id}: rc={rc}"
            );
            return Err(ErrUnspec);
        }

        resource.scanouts.enable(scanout_id);
        self.scanouts[scanout_idx] = Some(VirtioGpuScanout { resource_id });
        Ok(OkNoData)
    }

'''
text = text[:set_start] + new_set + text[flush_start:]

flush_start = text.index(flush_anchor)
blob_start = text.index(blob_anchor, flush_start)
new_flush = r'''    fn flush_resource(&mut self, resource_id: u32, _rect: virtio_gpu_rect) -> VirtioGpuResult {
        if self.gpu_backend.is_none() || resource_id == 0 {
            return Ok(OkNoData);
        }

        let resource = self
            .resources
            .get(&resource_id)
            .ok_or(ErrInvalidResourceId)?
            .clone();

        self.renderer.force_ctx_0();
        for scanout_id in resource.scanouts.iter_enabled() {
            // SAFETY: resource_id is known to the renderer and scanout_id comes
            // from AssociatedScanouts. The linked bridge performs a GPU-only
            // copy into the already-created Android HardwareBuffer.
            let rc = unsafe { vessel_ahb_update(resource_id, scanout_id) };
            if rc != 0 {
                error!(
                    "Vessel Android HardwareBuffer update failed for resource {resource_id}, scanout {scanout_id}: rc={rc}"
                );
                return Err(ErrUnspec);
            }
        }
        Ok(OkNoData)
    }

'''
text = text[:flush_start] + new_flush + text[blob_start:]

path.write_text(text)
final = path.read_text()
for needle in (
    marker,
    "vessel_ahb_set_scanout",
    "vessel_ahb_update",
    "vessel_ahb_disable",
    "self.renderer.force_ctx_0();",
    "GPU-only",
):
    if needle not in final:
        raise SystemExit(f"AHardwareBuffer patch verification failed: {needle}")

# The standard vhost-user-gpu display channel remains for EDID/cursor control,
# but the scanout hot path must no longer depend on EGL DMA-BUF export.
set_block = final[final.index(set_anchor):final.index(flush_anchor)]
if "export_resource_dmabuf" in set_block or "set_dmabuf_scanout" in set_block:
    raise SystemExit("legacy DMA-BUF export/send survived in set_scanout")

print("[vhost-gpu-patch] enabled Android HardwareBuffer VirGL scanout transport")
