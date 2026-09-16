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


# --- Android state/reporting fixes -------------------------------------------------
svc = "app/src/main/java/com/example/dreamlinux/VmSessionService.kt"
replace_once(
    svc,
    "                    awk '{printf \"VESSEL_UPTIME=%d\\\\n\",${'$'}1}' /proc/uptime\n",
    "                    awk '{printf \"VESSEL_UPTIME=%d\\\\n\",${'$'}1}' /proc/uptime\n"
    "                    printf 'VESSEL_CPUS='; getconf _NPROCESSORS_ONLN 2>/dev/null || nproc 2>/dev/null || echo ${VesselRuntimeController.UML_VCPUS}\n",
)
replace_once(svc, "                var uptime = 0L\n", "                var uptime = 0L\n                var vcpus = VesselRuntimeController.UML_VCPUS\n")
replace_once(
    svc,
    '                        line.contains("VESSEL_UPTIME=") -> uptime = line.substringAfter("VESSEL_UPTIME=").trim().toLongOrNull() ?: 0\n',
    '                        line.contains("VESSEL_UPTIME=") -> uptime = line.substringAfter("VESSEL_UPTIME=").trim().toLongOrNull() ?: 0\n'
    '                        line.contains("VESSEL_CPUS=") -> vcpus = line.substringAfter("VESSEL_CPUS=").trim().toIntOrNull() ?: VesselRuntimeController.UML_VCPUS\n',
)
replace_once(svc, "                    vcpus = 1,\n", "                    vcpus = vcpus,\n")

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

# Existing persistent images must actually install the package post-validation requires.
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

# --- VirGL synchronization hot-path fix -------------------------------------------
# Keep explicit GPU ordering, but stop creating a GL fence after submissions that
# only touch off-screen app resources. Track writers dirtied since the last active
# scanout flush and wait those only.
patch = "tools/vessel_native/patch_vhost_gpu_android_ahb.py"
replace_once(
    patch,
    'text = text.replace(resource_field, resource_field + "    pub vessel_contexts: HashSet<u32>,\\n", 1)',
    'text = text.replace(resource_field, resource_field + "    pub vessel_contexts: HashSet<u32>,\\n    pub vessel_dirty_contexts: HashSet<u32>,\\n", 1)',
)
replace_once(
    patch,
    'text = text.replace(resource_init, resource_init + "            vessel_contexts: HashSet::new(),\\n", 1)',
    'text = text.replace(resource_init, resource_init + "            vessel_contexts: HashSet::new(),\\n            vessel_dirty_contexts: HashSet::new(),\\n", 1)',
)

old_transfer_def = (
    "transfer_new = '''        self.renderer\\n"
    "            .transfer_write(resource_id, ctx_id, transfer.into(), None)?;\\n"
    "        self.resources\\n"
    "            .get_mut(&resource_id)\\n"
    "            .ok_or(ErrInvalidResourceId)?\\n"
    "            .vessel_contexts\\n"
    "            .insert(ctx_id);\\n"
    "        // SAFETY: process-local C ABI and virglrenderer left this context current.\\n"
    "        let sync_rc = unsafe { vessel_ahb_note_submit(ctx_id) };\\n"
    "        if sync_rc != 0 {\\n"
    "            error!(\"Vessel failed to publish VirGL transfer sync ctx={ctx_id} resource={resource_id}: rc={sync_rc}\");\\n"
    "            return Err(ErrUnspec);\\n"
    "        }\\n"
    "        Ok(OkNoData)\\n'''"
)
new_transfer_def = (
    "transfer_new = '''        self.renderer\\n"
    "            .transfer_write(resource_id, ctx_id, transfer.into(), None)?;\\n"
    "        let scanout_write = {\\n"
    "            let resource = self.resources\\n"
    "                .get_mut(&resource_id)\\n"
    "                .ok_or(ErrInvalidResourceId)?;\\n"
    "            resource.vessel_contexts.insert(ctx_id);\\n"
    "            if resource.scanouts.has_any_enabled() {\\n"
    "                resource.vessel_dirty_contexts.insert(ctx_id);\\n"
    "                true\\n"
    "            } else {\\n"
    "                false\\n"
    "            }\\n"
    "        };\\n"
    "        if scanout_write {\\n"
    "            // SAFETY: process-local C ABI and virglrenderer left this context current.\\n"
    "            let sync_rc = unsafe { vessel_ahb_note_submit(ctx_id) };\\n"
    "            if sync_rc != 0 {\\n"
    "                error!(\"Vessel failed to publish VirGL transfer sync ctx={ctx_id} resource={resource_id}: rc={sync_rc}\");\\n"
    "                return Err(ErrUnspec);\\n"
    "            }\\n"
    "        }\\n"
    "        Ok(OkNoData)\\n'''"
)
replace_once(patch, old_transfer_def, new_transfer_def)

replace_once(
    patch,
    '''        // virglrenderer has queued this context's command stream and leaves the
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
''',
    '''        // Only contexts that can directly write an active scanout need a
        // producer fence. Off-screen Firefox/KWin/app resources must not force
        // a glFenceSync+glFlush after every command submission.
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
''',
)

old_detach_def = "detach_new = '''        self.renderer.ctx_detach_resource(ctx_id, resource_id);\\n        if let Some(resource) = self.resources.get_mut(&resource_id) {\\n            resource.vessel_contexts.remove(&ctx_id);\\n        }\\n        Ok(OkNoData)\\n'''"
new_detach_def = "detach_new = '''        self.renderer.ctx_detach_resource(ctx_id, resource_id);\\n        if let Some(resource) = self.resources.get_mut(&resource_id) {\\n            resource.vessel_contexts.remove(&ctx_id);\\n            resource.vessel_dirty_contexts.remove(&ctx_id);\\n        }\\n        Ok(OkNoData)\\n'''"
replace_once(patch, old_detach_def, new_detach_def)

old_destroy_def = "destroy_new = '''        self.renderer.destroy_context(ctx_id);\\n        for resource in self.resources.values_mut() {\\n            resource.vessel_contexts.remove(&ctx_id);\\n        }\\n        Ok(OkNoData)\\n'''"
new_destroy_def = "destroy_new = '''        self.renderer.destroy_context(ctx_id);\\n        for resource in self.resources.values_mut() {\\n            resource.vessel_contexts.remove(&ctx_id);\\n            resource.vessel_dirty_contexts.remove(&ctx_id);\\n        }\\n        Ok(OkNoData)\\n'''"
replace_once(patch, old_destroy_def, new_destroy_def)

replace_once(patch, "        for ctx_id in &resource.vessel_contexts {\n", "        for ctx_id in &resource.vessel_dirty_contexts {\n")
replace_once(
    patch,
    "        }\n\n        for scanout_id in resource.scanouts.iter_enabled() {\n",
    "        }\n        if let Some(current) = self.resources.get_mut(&resource_id) {\n"
    "            for ctx_id in &resource.vessel_dirty_contexts {\n"
    "                current.vessel_dirty_contexts.remove(ctx_id);\n"
    "            }\n"
    "        }\n\n        for scanout_id in resource.scanouts.iter_enabled() {\n",
)
replace_once(patch, '    "vessel_contexts",\n', '    "vessel_contexts",\n    "vessel_dirty_contexts",\n')

rebuild = "tools/vessel_native/rebuild_vhost_gpu_ahb.sh"
replace_once(
    rebuild,
    "grep -Fq 'vessel_contexts' \"$VHOST/vhost-device-gpu/src/backend/virgl.rs\"\n",
    "grep -Fq 'vessel_contexts' \"$VHOST/vhost-device-gpu/src/backend/virgl.rs\"\n"
    "grep -Fq 'vessel_dirty_contexts' \"$VHOST/vhost-device-gpu/src/backend/virgl.rs\"\n",
)

print("alpha3b stabilization migration applied")
