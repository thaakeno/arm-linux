#!/usr/bin/env python3
from __future__ import annotations

import pathlib
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_vhost_gpu_android_ahb.py <vhost-device source dir>")

root = pathlib.Path(sys.argv[1])
path = root / "vhost-device-gpu/src/backend/virgl.rs"
text = path.read_text()
marker = "VESSEL_ANDROID_AHB_SCANOUT_V2"
if marker in text:
    print("[vhost-gpu-patch] synchronized Android HardwareBuffer scanout patch already applied")
    raise SystemExit(0)

const_anchor = "const CAPSET_ID_VENUS: u32 = 4;\n"
ffi = r'''

// VESSEL_ANDROID_AHB_SCANOUT_V2
// VirGL stays fully GPU accelerated. Every submitted VirGL context publishes a
// GPU sync object into the bridge; scanout context 0 waits on those GPU-side
// sync objects before reading the shared texture. The finished AHB copy is then
// handed to Vulkan with an Android native sync FD. No CPU readback, glFinish,
// software renderer, or TCP framebuffer path is involved.
extern "C" {
    fn vessel_ahb_note_submit(ctx_id: u32) -> libc::c_int;
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

submit_anchor = "    fn submit_command("
fence_anchor = "    fn create_fence("
set_anchor = "    fn set_scanout("
flush_anchor = "    fn flush_resource("
blob_anchor = "    fn resource_create_blob("
for anchor in (submit_anchor, fence_anchor, set_anchor, flush_anchor, blob_anchor):
    if text.count(anchor) != 1:
        raise SystemExit(f"unexpected virgl Renderer method layout: {anchor}")

submit_start = text.index(submit_anchor)
fence_start = text.index(fence_anchor, submit_start)
new_submit = r'''    fn submit_command(
        &mut self,
        ctx_id: u32,
        commands: &mut [u8],
        fence_ids: &[u64],
    ) -> VirtioGpuResult {
        if !self.context_ids.contains(&ctx_id) {
            return Err(ErrInvalidContextId);
        }
        self.renderer
            .submit_cmd(ctx_id, commands, fence_ids)
            .map_err(|_| ErrUnspec)?;

        // virglrenderer has just submitted this context's GL command stream and
        // leaves its shared host context current. Publish a server-side GL sync
        // now so a later scanout flush can wait on every rendering context on
        // the GPU without stalling the CPU with glFinish().
        // SAFETY: process-local C ABI, scalar context ID only.
        let sync_rc = unsafe { vessel_ahb_note_submit(ctx_id) };
        if sync_rc != 0 {
            error!("Vessel failed to publish VirGL context sync ctx={ctx_id}: rc={sync_rc}");
            return Err(ErrUnspec);
        }
        Ok(OkNoData)
    }

'''
text = text[:submit_start] + new_submit + text[fence_start:]

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
            // SAFETY: process-local C ABI; scanout index was validated above.
            let rc = unsafe { vessel_ahb_disable(scanout_id) };
            if rc != 0 {
                error!("Vessel AHardwareBuffer scanout disable failed: rc={rc}");
                return Err(ErrUnspec);
            }
            return Ok(OkNoData);
        }

        if !self.resources.contains_key(&resource_id) {
            return Err(ErrInvalidResourceId);
        }

        // Context 0 owns the scanout copy. The bridge first waits, on-GPU, for
        // the context sync objects recorded after guest render submissions.
        self.renderer.force_ctx_0();
        // SAFETY: validated scalar values; bridge uses the public virglrenderer
        // resource API while context 0 is current.
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
            error!("Vessel Android HardwareBuffer scanout failed for resource {resource_id}: rc={rc}");
            return Err(ErrUnspec);
        }

        let resource = self.resources.get_mut(&resource_id).ok_or(ErrInvalidResourceId)?;
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
            // SAFETY: resource and scanout IDs originate from renderer state.
            // The bridge waits on render-context GL syncs, performs a GPU blit
            // to AHB, and exports a native producer fence for Vulkan.
            let rc = unsafe { vessel_ahb_update(resource_id, scanout_id) };
            if rc != 0 {
                error!("Vessel Android HardwareBuffer update failed for resource {resource_id}, scanout {scanout_id}: rc={rc}");
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
    "vessel_ahb_note_submit",
    "vessel_ahb_set_scanout",
    "vessel_ahb_update",
    "vessel_ahb_disable",
    "self.renderer.force_ctx_0();",
    "glFinish()",
):
    if needle not in final:
        raise SystemExit(f"AHardwareBuffer patch verification failed: {needle}")

set_block = final[final.index(set_anchor):final.index(flush_anchor)]
if "export_resource_dmabuf" in set_block or "set_dmabuf_scanout" in set_block:
    raise SystemExit("legacy DMA-BUF export/send survived in set_scanout")

print("[vhost-gpu-patch] enabled synchronized AHardwareBuffer + native-fence VirGL scanout")
