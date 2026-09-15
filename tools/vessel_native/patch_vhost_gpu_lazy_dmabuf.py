#!/usr/bin/env python3
from __future__ import annotations

import pathlib
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_vhost_gpu_lazy_dmabuf.py <vhost-device source dir>")

root = pathlib.Path(sys.argv[1])
path = root / "vhost-device-gpu/src/backend/virgl.rs"
text = path.read_text()

marker = "VESSEL_LAZY_DMABUF_SCANOUT_V1"
if marker in text:
    print("[vhost-gpu-patch] lazy DMA-BUF scanout export already applied")
    raise SystemExit(0)

old = r'''        // Handling non-zero resource_id (Enable/Update Scanout)
        let resource = self
            .resources
            .get_mut(&resource_id)
            .ok_or(ErrInvalidResourceId)?;

        // Extract the DMABUF information (handle and info_3d)
        let handle = resource.virgl_resource.handle.as_ref().ok_or_else(|| {
            error!("resource {resource_id} has no handle");
            ErrUnspec
        })?;
'''

new = r'''        // VESSEL_LAZY_DMABUF_SCANOUT_V1
        // VirglRenderer::create_3d can successfully query texture metadata while
        // leaving handle=None when blob export is unavailable. Xorg/glamor then
        // reaches SET_SCANOUT with a perfectly valid VirGL resource but upstream
        // vhost-device-gpu rejects it before trying the classic texture export.
        // Export the resource lazily at the moment a scanout actually needs an
        // FD. This stays on the direct DMA-BUF path; no RGB/readback fallback is
        // introduced.
        let needs_export = self
            .resources
            .get(&resource_id)
            .ok_or(ErrInvalidResourceId)?
            .virgl_resource
            .handle
            .is_none();

        if needs_export {
            let exported = self.renderer.export_resource_dmabuf(resource_id).map_err(|e| {
                error!("failed to export DMA-BUF for scanout resource {resource_id}: {e:?}");
                ErrUnspec
            })?;
            self.resources
                .get_mut(&resource_id)
                .ok_or(ErrInvalidResourceId)?
                .virgl_resource
                .handle = Some(exported);
            debug!("lazily exported DMA-BUF for scanout resource {resource_id}");
        }

        let resource = self
            .resources
            .get_mut(&resource_id)
            .ok_or(ErrInvalidResourceId)?;

        let handle = resource.virgl_resource.handle.as_ref().ok_or_else(|| {
            error!("resource {resource_id} still has no handle after DMA-BUF export");
            ErrUnspec
        })?;
'''

if text.count(old) != 1:
    raise SystemExit("unexpected vhost-device-gpu set_scanout resource/handle block")
text = text.replace(old, new, 1)

old_dims = r'''            fd_width: info_3d.width,
            fd_height: info_3d.height,
'''
new_dims = r'''            // virglrenderer query metadata can report 0x0 even though the
            // VirglResource itself has the real allocation dimensions.
            fd_width: if info_3d.width != 0 {
                info_3d.width
            } else {
                resource.virgl_resource.width
            },
            fd_height: if info_3d.height != 0 {
                info_3d.height
            } else {
                resource.virgl_resource.height
            },
'''
if text.count(old_dims) != 1:
    raise SystemExit("unexpected vhost-device-gpu DMA-BUF dimension block")
text = text.replace(old_dims, new_dims, 1)

path.write_text(text)
final = path.read_text()
for needle in (
    marker,
    "export_resource_dmabuf(resource_id)",
    "lazily exported DMA-BUF for scanout resource",
    "resource.virgl_resource.width",
    "resource.virgl_resource.height",
    "set_dmabuf_scanout",
):
    if needle not in final:
        raise SystemExit(f"lazy DMA-BUF patch verification failed: {needle}")

print("[vhost-gpu-patch] enabled lazy direct DMA-BUF export for VirGL scanouts")
