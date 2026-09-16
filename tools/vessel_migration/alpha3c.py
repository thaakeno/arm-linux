#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(rel):
    return (ROOT / rel).read_text()


def write(rel, text):
    (ROOT / rel).write_text(text)


def replace_once(rel, old, new):
    text = read(rel)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{rel}: expected one occurrence, got {count}: {old[:180]!r}")
    write(rel, text.replace(old, new, 1))


# Android System tab: stop overwriting the real six-vCPU configuration with 1.
svc = "app/src/main/java/com/example/dreamlinux/VmSessionService.kt"
replace_once(svc, "                    vcpus = 1,\n", "                    vcpus = VesselRuntimeController.UML_VCPUS,\n")

# Do not leave a failed post-start validation looking like a 97% healthy boot.
replace_once(
    svc,
    '''            } catch (t: Throwable) {
                if (op != operationGeneration || state.value.stage == "stopping") return@launch
                runCatching { runtime.status() }.getOrNull()?.let(::applyState)
                state.value = state.value.copy(
                    lastError = t.message ?: t.javaClass.simpleName,
                    message = t.message ?: "Startup failed",
                )
            } finally {
                if (op == operationGeneration) {
                    val current = state.value
                    state.value = current.copy(
                        busy = false,
                        stage = if (current.running) "ready" else "idle",
                    )
                }
            }
''',
    '''            } catch (t: Throwable) {
                if (op != operationGeneration || state.value.stage == "stopping") return@launch
                runCatching { runtime.status() }.getOrNull()?.let(::applyState)
                val message = t.message ?: t.javaClass.simpleName
                state.value = state.value.copy(
                    lastError = message,
                    progressPercent = 0,
                    progressDetail = "Workstation setup failed",
                    message = message,
                    stage = "setup_error",
                )
            } finally {
                if (op == operationGeneration) {
                    val current = state.value
                    state.value = current.copy(
                        busy = false,
                        stage = when {
                            current.lastError.isNotBlank() -> "setup_error"
                            current.running -> "ready"
                            else -> "idle"
                        },
                    )
                }
            }
''',
)

# Persistent alpha2 images currently pass the early Plasma check without the
# emoji font that the 97% workstation validator requires. Make it part of both
# readiness and provisioning so old images are repaired automatically.
rt = "app/src/main/java/com/example/dreamlinux/VesselRuntimeController.kt"
replace_once(
    rt,
    "qml-module-qtquick-templates2 qml-module-qtgraphicaleffects plasma-integration libkf5service-data; do ",
    "qml-module-qtquick-templates2 qml-module-qtgraphicaleffects plasma-integration libkf5service-data fonts-noto-color-emoji; do ",
)
replace_once(
    rt,
    "fonts-noto-core fonts-dejavu-core fonts-liberation firefox-esr konsole dolphin ark kcalc okular gwenview kate && ",
    "fonts-noto-core fonts-noto-color-emoji fonts-dejavu-core fonts-liberation firefox-esr konsole dolphin ark kcalc okular gwenview kate && ",
)

# Patch the generated vhost-device Rust after the existing V3 transformation.
# This keeps its explicit sync design, but only creates/waits producer GL fences
# for contexts that dirtied an ACTIVE scanout since the previous flush. The old
# code fenced every Firefox/app submission, including unrelated off-screen
# resources, which forces needless glFlush traffic through ANGLE.
patch = "tools/vessel_native/patch_vhost_gpu_android_ahb.py"
anchor = "path.write_text(text)\nfinal = path.read_text()\n"
optimization = r'''# VESSEL_SCANOUT_DIRTY_CONTEXT_SYNC
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
'''
replace_once(patch, anchor, optimization)

# Native rebuild CI must verify that the generated adapter includes the new
# dirty-writer state, not merely the older historical context set.
rebuild = "tools/vessel_native/rebuild_vhost_gpu_ahb.sh"
replace_once(
    rebuild,
    "grep -Fq 'vessel_contexts' \"$VHOST/vhost-device-gpu/src/backend/virgl.rs\"\n",
    "grep -Fq 'vessel_contexts' \"$VHOST/vhost-device-gpu/src/backend/virgl.rs\"\n"
    "grep -Fq 'vessel_dirty_contexts' \"$VHOST/vhost-device-gpu/src/backend/virgl.rs\"\n",
)

print("alpha3c stabilization migration applied")
