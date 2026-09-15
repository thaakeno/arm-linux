#!/usr/bin/env python3
from __future__ import annotations

import pathlib
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_vhost_device_gpu_raw_scanout.py <vhost-device source dir>")

root = pathlib.Path(sys.argv[1])
path = root / "vhost-device-gpu/src/backend/virgl.rs"
text = path.read_text()

if "VESSEL_RAW_SCANOUT_V1" in text:
    print("[vhost-gpu-patch] raw scanout patch already applied")
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

replacement = r'''    // VESSEL_RAW_SCANOUT_V1
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
        let bytes = (width as usize)
            .checked_mul(height as usize)
            .and_then(|n| n.checked_mul(4))
            .ok_or(ErrUnspec)?;
        let mut data = vec![0u8; bytes];

        let transfer = Transfer3DDesc::new_2d(0, 0, width, height, 0);
        self.renderer
            .transfer_read(
                resource_id,
                0,
                transfer.into(),
                Some(IoSliceMut::new(&mut data)),
            )
            .map_err(|_| ErrUnspec)?;

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
for needle in ("VESSEL_RAW_SCANOUT_V1", "VhostUserGpuScanout", ".update_scanout(", ".transfer_read("):
    if needle not in final:
        raise SystemExit(f"raw scanout patch verification failed: {needle}")
print("[vhost-gpu-patch] enabled standard raw scanout transport")
