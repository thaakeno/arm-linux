#!/usr/bin/env python3
from __future__ import annotations

import pathlib
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_vhost_gpu_android_ahb.py <vhost-device source dir>")

root = pathlib.Path(sys.argv[1])
path = root / "vhost-device-gpu/src/backend/virgl.rs"
text = path.read_text()
marker = "VESSEL_ANDROID_AHB_SCANOUT_V3"
if marker in text:
    print("[vhost-gpu-patch] resource-scoped Android HardwareBuffer scanout patch already applied")
    raise SystemExit(0)

const_anchor = "const CAPSET_ID_VENUS: u32 = 4;\n"
ffi = r'''

// VESSEL_ANDROID_AHB_SCANOUT_V3
// VirGL remains GPU accelerated. Each VirGL context publishes its latest
// producer GL sync, while each resource records only the contexts attached to
// or explicitly writing that resource. Scanout context 0 waits only those
// producer contexts before reading the resource. The AHB copy then exports an
// Android native sync FD to Vulkan. No CPU readback, global all-context barrier,
// glFinish, software renderer, or framebuffer streaming path is involved.
extern "C" {
    fn vessel_ahb_note_submit(ctx_id: u32) -> libc::c_int;
    fn vessel_ahb_wait_context(ctx_id: u32) -> libc::c_int;
    fn vessel_ahb_set_scanout(
        resource_id: u32,
        scanout_id: u32,
        x: u32,
        y: u32,
        width: u32,
        height: u32,
    ) -> libc::c_int;
    fn vessel_ahb_update(
        resource_id: u32,
        scanout_id: u32,
        x: u32,
        y: u32,
        width: u32,
        height: u32,
    ) -> libc::c_int;
    fn vessel_ahb_disable(scanout_id: u32) -> libc::c_int;
}
'''
if text.count(const_anchor) != 1:
    raise SystemExit("unexpected virgl capset constants")
text = text.replace(const_anchor, const_anchor + ffi, 1)

# Track which VirGL contexts can produce each resource. This gives scanout a
# real resource dependency set instead of serializing every context in the VM.
resource_field = "    pub uuid: Uuid,\n"
if text.count(resource_field) != 1:
    raise SystemExit("unexpected GpuResource layout")
text = text.replace(resource_field, resource_field + "    pub vessel_contexts: HashSet<u32>,\n", 1)
resource_init = "            uuid: Uuid::new_v4(),\n"
if text.count(resource_init) != 1:
    raise SystemExit("unexpected GpuResource initializer")
text = text.replace(resource_init, resource_init + "            vessel_contexts: HashSet::new(),\n", 1)

# Transfer writes are resource-writing paths too. Publish the producer context
# after virglrenderer has queued the write and remember it on this resource even
# if that transfer path did not go through ctx_attach_resource first.
transfer1 = '''        self.renderer\n            .transfer_write(resource_id, ctx_id, transfer.into(), None)?;\n        Ok(OkNoData)\n'''
transfer_new = '''        self.renderer\n            .transfer_write(resource_id, ctx_id, transfer.into(), None)?;\n        self.resources\n            .get_mut(&resource_id)\n            .ok_or(ErrInvalidResourceId)?\n            .vessel_contexts\n            .insert(ctx_id);\n        // SAFETY: process-local C ABI and virglrenderer left this context current.\n        let sync_rc = unsafe { vessel_ahb_note_submit(ctx_id) };\n        if sync_rc != 0 {\n            error!("Vessel failed to publish VirGL transfer sync ctx={ctx_id} resource={resource_id}: rc={sync_rc}");\n            return Err(ErrUnspec);\n        }\n        Ok(OkNoData)\n'''
if text.count(transfer1) != 2:
    raise SystemExit("unexpected transfer_write layouts")
text = text.replace(transfer1, transfer_new, 2)

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

        // virglrenderer has queued this context's command stream and leaves the
        // shared host GL context current. Publish only this context's newest
        // producer fence; resource flush later waits it iff the resource is
        // actually attached to this context.
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

# Keep resource<->context membership synchronized with virglrenderer.
attach_old = '''        self.renderer.ctx_attach_resource(ctx_id, resource_id);\n        Ok(OkNoData)\n'''
attach_new = '''        self.renderer.ctx_attach_resource(ctx_id, resource_id);\n        self.resources\n            .get_mut(&resource_id)\n            .ok_or(ErrInvalidResourceId)?\n            .vessel_contexts\n            .insert(ctx_id);\n        Ok(OkNoData)\n'''
if text.count(attach_old) != 1:
    raise SystemExit("unexpected context_attach_resource layout")
text = text.replace(attach_old, attach_new, 1)

detach_old = '''        self.renderer.ctx_detach_resource(ctx_id, resource_id);\n        Ok(OkNoData)\n'''
detach_new = '''        self.renderer.ctx_detach_resource(ctx_id, resource_id);\n        if let Some(resource) = self.resources.get_mut(&resource_id) {\n            resource.vessel_contexts.remove(&ctx_id);\n        }\n        Ok(OkNoData)\n'''
if text.count(detach_old) != 1:
    raise SystemExit("unexpected context_detach_resource layout")
text = text.replace(detach_old, detach_new, 1)

destroy_old = '''        self.renderer.destroy_context(ctx_id);\n        Ok(OkNoData)\n'''
destroy_new = '''        self.renderer.destroy_context(ctx_id);\n        for resource in self.resources.values_mut() {\n            resource.vessel_contexts.remove(&ctx_id);\n        }\n        Ok(OkNoData)\n'''
if text.count(destroy_old) != 1:
    raise SystemExit("unexpected destroy_context layout")
text = text.replace(destroy_old, destroy_new, 1)

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

        let producer_contexts = self
            .resources
            .get(&resource_id)
            .ok_or(ErrInvalidResourceId)?
            .vessel_contexts
            .clone();

        // Context 0 owns the scanout copy. Wait only contexts that can produce
        // this resource, never every active VirGL context in the process.
        self.renderer.force_ctx_0();
        for ctx_id in producer_contexts {
            // SAFETY: process-local C ABI; producer context came from the
            // resource's virgl context-attachment/write set.
            let rc = unsafe { vessel_ahb_wait_context(ctx_id) };
            if rc != 0 {
                error!("Vessel resource-scoped sync failed resource={resource_id} ctx={ctx_id}: rc={rc}");
                return Err(ErrUnspec);
            }
        }

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
new_flush = r'''    fn flush_resource(&mut self, resource_id: u32, rect: virtio_gpu_rect) -> VirtioGpuResult {
        if self.gpu_backend.is_none() || resource_id == 0 {
            return Ok(OkNoData);
        }

        let resource = self
            .resources
            .get(&resource_id)
            .ok_or(ErrInvalidResourceId)?
            .clone();

        // Resource-scoped ordering: only producer contexts attached to or
        // explicitly writing this resource are dependencies of this scanout.
        self.renderer.force_ctx_0();
        for ctx_id in &resource.vessel_contexts {
            // SAFETY: process-local C ABI; IDs are renderer-owned.
            let rc = unsafe { vessel_ahb_wait_context(*ctx_id) };
            if rc != 0 {
                error!("Vessel resource-scoped sync failed resource={resource_id} ctx={ctx_id}: rc={rc}");
                return Err(ErrUnspec);
            }
        }

        for scanout_id in resource.scanouts.iter_enabled() {
            // Preserve virtio-gpu's damage rectangle all the way into the AHB
            // bridge. The bridge accumulates damage per triple-buffer slot so
            // partial copies remain correct even when a slot is reused later.
            // SAFETY: resource and scanout IDs originate from renderer state.
            let rc = unsafe {
                vessel_ahb_update(
                    resource_id,
                    scanout_id,
                    rect.x.into(),
                    rect.y.into(),
                    rect.width.into(),
                    rect.height.into(),
                )
            };
            if rc != 0 {
                error!("Vessel Android HardwareBuffer update failed for resource {resource_id}, scanout {scanout_id}: rc={rc}");
                return Err(ErrUnspec);
            }
        }
        Ok(OkNoData)
    }

'''
text = text[:flush_start] + new_flush + text[blob_start:]

# VESSEL_SCANOUT_DIRTY_CONTEXT_SYNC
# Narrow the V3 resource context set to writers that dirtied an active scanout
# since its previous flush. This preserves resource ordering without fencing
# every off-screen VirGL command stream in the VM.
text = text.replace(
    "    pub vessel_contexts: HashSet<u32>,\n",
    "    pub vessel_contexts: HashSet<u32>,\n    pub vessel_dirty_contexts: HashSet<u32>,\n",
    1,
)
text = text.replace(
    "            vessel_contexts: HashSet::new(),\n",
    "            vessel_contexts: HashSet::new(),\n            vessel_dirty_contexts: HashSet::new(),\n",
    1,
)

transfer_old = '''        self.renderer
            .transfer_write(resource_id, ctx_id, transfer.into(), None)?;
        self.resources
            .get_mut(&resource_id)
            .ok_or(ErrInvalidResourceId)?
            .vessel_contexts
            .insert(ctx_id);
        // SAFETY: process-local C ABI and virglrenderer left this context current.
        let sync_rc = unsafe { vessel_ahb_note_submit(ctx_id) };
        if sync_rc != 0 {
            error!("Vessel failed to publish VirGL transfer sync ctx={ctx_id} resource={resource_id}: rc={sync_rc}");
            return Err(ErrUnspec);
        }
        Ok(OkNoData)
'''
transfer_new_dirty = '''        self.renderer
            .transfer_write(resource_id, ctx_id, transfer.into(), None)?;
        let scanout_write = {
            let resource = self.resources
                .get_mut(&resource_id)
                .ok_or(ErrInvalidResourceId)?;
            resource.vessel_contexts.insert(ctx_id);
            if resource.scanouts.has_any_enabled() {
                resource.vessel_dirty_contexts.insert(ctx_id);
                true
            } else {
                false
            }
        };
        if scanout_write {
            // SAFETY: process-local C ABI and virglrenderer left this context current.
            let sync_rc = unsafe { vessel_ahb_note_submit(ctx_id) };
            if sync_rc != 0 {
                error!("Vessel failed to publish active-scanout transfer sync ctx={ctx_id} resource={resource_id}: rc={sync_rc}");
                return Err(ErrUnspec);
            }
        }
        Ok(OkNoData)
'''
if text.count(transfer_old) != 2:
    raise SystemExit(f"unexpected generated transfer sync blocks: {text.count(transfer_old)}")
text = text.replace(transfer_old, transfer_new_dirty, 2)

submit_old = '''        // virglrenderer has queued this context's command stream and leaves the
        // shared host GL context current. Publish only this context's newest
        // producer fence; resource flush later waits it iff the resource is
        // actually attached to this context.
        // SAFETY: process-local C ABI, scalar context ID only.
        let sync_rc = unsafe { vessel_ahb_note_submit(ctx_id) };
        if sync_rc != 0 {
            error!("Vessel failed to publish VirGL context sync ctx={ctx_id}: rc={sync_rc}");
            return Err(ErrUnspec);
        }
        Ok(OkNoData)
'''
submit_new_dirty = '''        // Only a context that can directly write an active scanout needs a
        // producer fence. Off-screen Firefox/KWin/app resources stay fully GPU
        // accelerated without a glFenceSync+glFlush after every submission.
        let mut scanout_write = false;
        for resource in self.resources.values_mut() {
            if resource.scanouts.has_any_enabled() && resource.vessel_contexts.contains(&ctx_id) {
                resource.vessel_dirty_contexts.insert(ctx_id);
                scanout_write = true;
            }
        }
        if scanout_write {
            // SAFETY: process-local C ABI, scalar context ID only.
            let sync_rc = unsafe { vessel_ahb_note_submit(ctx_id) };
            if sync_rc != 0 {
                error!("Vessel failed to publish active-scanout VirGL sync ctx={ctx_id}: rc={sync_rc}");
                return Err(ErrUnspec);
            }
        }
        Ok(OkNoData)
'''
if text.count(submit_old) != 1:
    raise SystemExit("unexpected generated submit sync block")
text = text.replace(submit_old, submit_new_dirty, 1)

text = text.replace(
    "            resource.vessel_contexts.remove(&ctx_id);\n        }\n        Ok(OkNoData)\n",
    "            resource.vessel_contexts.remove(&ctx_id);\n            resource.vessel_dirty_contexts.remove(&ctx_id);\n        }\n        Ok(OkNoData)\n",
    1,
)
text = text.replace(
    "            resource.vessel_contexts.remove(&ctx_id);\n        }\n        Ok(OkNoData)\n",
    "            resource.vessel_contexts.remove(&ctx_id);\n            resource.vessel_dirty_contexts.remove(&ctx_id);\n        }\n        Ok(OkNoData)\n",
    1,
)

flush_start_dirty = text.index("    fn flush_resource(")
blob_start_dirty = text.index("    fn resource_create_blob(", flush_start_dirty)
flush_dirty = text[flush_start_dirty:blob_start_dirty]
if flush_dirty.count("for ctx_id in &resource.vessel_contexts") != 1:
    raise SystemExit("unexpected flush resource context wait")
flush_dirty = flush_dirty.replace(
    "for ctx_id in &resource.vessel_contexts",
    "for ctx_id in &resource.vessel_dirty_contexts",
    1,
)
flush_dirty = flush_dirty.replace(
    "        for scanout_id in resource.scanouts.iter_enabled() {\n",
    "        if let Some(current) = self.resources.get_mut(&resource_id) {\n"
    "            for ctx_id in &resource.vessel_dirty_contexts {\n"
    "                current.vessel_dirty_contexts.remove(ctx_id);\n"
    "            }\n"
    "        }\n\n"
    "        for scanout_id in resource.scanouts.iter_enabled() {\n",
    1,
)
text = text[:flush_start_dirty] + flush_dirty + text[blob_start_dirty:]

path.write_text(text)
final = path.read_text()
for needle in (
    marker,
    "vessel_ahb_note_submit",
    "vessel_ahb_wait_context",
    "vessel_contexts",
    "vessel_ahb_set_scanout",
    "vessel_ahb_update",
    "vessel_ahb_disable",
    "self.renderer.force_ctx_0();",
):
    if needle not in final:
        raise SystemExit(f"AHardwareBuffer patch verification failed: {needle}")

set_block = final[final.index(set_anchor):final.index(flush_anchor)]
if "export_resource_dmabuf" in set_block or "set_dmabuf_scanout" in set_block:
    raise SystemExit("legacy DMA-BUF export/send survived in set_scanout")

print("[vhost-gpu-patch] enabled resource-scoped VirGL sync + damage-aware AHardwareBuffer scanout")
