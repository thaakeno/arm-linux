#!/usr/bin/env python3
from __future__ import annotations

import pathlib
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_vhost_device_gpu_raw_scanout.py <vhost-device source dir>")

root = pathlib.Path(sys.argv[1])
path = root / "vhost-device-gpu/src/backend/virgl.rs"
text = path.read_text()

if "VESSEL_RAW_SCANOUT_V2" in text:
    print("[vhost-gpu-patch] raw scanout v2 patch already applied")
    raise SystemExit(0)

old_import = """        VhostUserGpuCursorPos, VhostUserGpuDMABUFScanout, VhostUserGpuDMABUFScanout2,\n        VhostUserGpuEdidRequest, VhostUserGpuUpdate,\n"""
new_import = """        VhostUserGpuCursorPos, VhostUserGpuEdidRequest, VhostUserGpuScanout,\n        VhostUserGpuUpdate,\n"""
if old_import not in text:
    raise SystemExit("unexpected virgl GPU message import block")
text = text.replace(old_import, new_import, 1)

start = text.find("    fn set_scanout(\n", text.find("impl Renderer for VirglRendererAdapter"))
end = text.find("    fn resource_create_blob(\n", start)
if start < 0 or end < 0:
    raise SystemExit("could not locate VirGL scanout methods")

replacement = r'''    // VESSEL_RAW_SCANOUT_V2
    // Android app sandboxes cannot receive SCM_RIGHTS directly from Termux.
    // Keep 3D rendering fully accelerated in virglrenderer/ANGLE/Adreno, but
    // use the standard vhost-user-gpu software scanout messages for the final
    // cross-app hop. This is damage/frame transport, not screenshot polling.
    fn set_scanout(
        &mut self,
        scanout_id: u32,
        resource_id: u32,
        rect: virtio_gpu_rect,
    ) -> VirtioGpuResult {
        let Some(gpu_backend) = self.gpu_backend.as_ref() else {
            return Ok(OkNoData);
        };

        let scanout_idx = scanout_id as usize;
        if scanout_idx >= VIRTIO_GPU_MAX_SCANOUTS as usize {
            return Err(ErrInvalidScanoutId);
        }

        if let Some(old) = &self.scanouts[scanout_idx] {
            if old.resource_id != resource_id {
                if let Some(old_resource) = self.resources.get_mut(&old.resource_id) {
                    old_resource.scanouts.disable(scanout_id);
                }
            }
        }

        if resource_id == 0 {
            common_set_scanout_disable(&mut self.scanouts, scanout_idx);
            gpu_backend
                .set_scanout(&VhostUserGpuScanout {
                    scanout_id,
                    width: 0,
                    height: 0,
                })
                .map_err(|e| {
                    error!("Failed to disable Vessel raw scanout: {e:?}");
                    ErrUnspec
                })?;
            return Ok(OkNoData);
        }

        let resource = self
            .resources
            .get_mut(&resource_id)
            .ok_or(ErrInvalidResourceId)?;

        gpu_backend
            .set_scanout(&VhostUserGpuScanout {
                scanout_id,
                width: rect.width.into(),
                height: rect.height.into(),
            })
            .map_err(|e| {
                error!("Failed to enable Vessel raw scanout: {e:?}");
                ErrUnspec
            })?;

        resource.scanouts.enable(scanout_id);
        self.scanouts[scanout_idx] = Some(VirtioGpuScanout { resource_id });
        Ok(OkNoData)
    }

    fn flush_resource(&mut self, resource_id: u32, _rect: virtio_gpu_rect) -> VirtioGpuResult {
        let Some(gpu_backend) = self.gpu_backend.as_ref() else {
            return Ok(OkNoData);
        };
        if resource_id == 0 {
            return Ok(OkNoData);
        }

        let resource = self
            .resources
            .get(&resource_id)
            .ok_or(ErrInvalidResourceId)?
            .clone();
        let width = resource.virgl_resource.width;
        let height = resource.virgl_resource.height;
        let stride = width.checked_mul(4).ok_or(ErrUnspec)?;
        let bytes = (stride as usize)
            .checked_mul(height as usize)
            .ok_or(ErrUnspec)?;
        let mut data = vec![0u8; bytes];

        // virglrenderer needs an explicit readback stride here. Leaving stride
        // at zero produced successful transfers containing only black pixels on
        // the Android/ANGLE backend. This matches the working libkrun scanout
        // readback path: ctx 0, tightly packed width*4 rows, layer_stride 0.
        let transfer = Transfer3DDesc {
            x: 0,
            y: 0,
            z: 0,
            w: width,
            h: height,
            d: 1,
            level: 0,
            stride,
            layer_stride: 0,
            offset: 0,
        };
        self.renderer
            .transfer_read(
                resource_id,
                0,
                transfer.into(),
                Some(IoSliceMut::new(&mut data)),
            )
            .map_err(|_| ErrUnspec)?;

        // Ignore genuinely blank readbacks. In v1 these were forwarded to the
        // Android presenter and made Vessel report VISIBLE while showing a black
        // Surface. We only advance the display pipeline once RGB content exists.
        // Byte 3 is intentionally ignored because XRGB may keep it non-zero even
        // for a visually black pixel.
        let has_visible_rgb = data
            .chunks_exact(4)
            .step_by(32)
            .any(|px| px[0] != 0 || px[1] != 0 || px[2] != 0);
        if !has_visible_rgb {
            trace!("Vessel raw scanout readback is still black resource={resource_id} {width}x{height}");
            return Ok(OkNoData);
        }

        for scanout_id in resource.scanouts.iter_enabled() {
            gpu_backend
                .update_scanout(
                    &VhostUserGpuUpdate {
                        scanout_id,
                        x: 0,
                        y: 0,
                        width,
                        height,
                    },
                    &data,
                )
                .map_err(|e| {
                    error!("Failed to update Vessel raw scanout: {e:?}");
                    ErrUnspec
                })?;
        }
        Ok(OkNoData)
    }

'''

text = text[:start] + replacement + text[end:]
path.write_text(text)

final = path.read_text()
for needle in (
    "VESSEL_RAW_SCANOUT_V2",
    "VhostUserGpuScanout",
    ".update_scanout(",
    ".transfer_read(",
    "stride = width.checked_mul(4)",
    "has_visible_rgb",
):
    if needle not in final:
        raise SystemExit(f"raw scanout v2 patch verification failed: {needle}")
print("[vhost-gpu-patch] enabled raw scanout v2 with explicit readback stride + black-frame suppression")
