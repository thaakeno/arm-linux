#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(rel: str) -> str:
    return (ROOT / rel).read_text()


def write(rel: str, text: str) -> None:
    (ROOT / rel).write_text(text)


def replace_once(rel: str, old: str, new: str) -> None:
    text = read(rel)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{rel}: expected one occurrence, got {count}: {old[:160]!r}")
    write(rel, text.replace(old, new, 1))


# ---------------------------------------------------------------------------
# 1. Fix the two proven Android/service bugs from the physical-device log.
# ---------------------------------------------------------------------------
svc = "app/src/main/java/com/example/dreamlinux/VmSessionService.kt"

replace_once(
    svc,
    '                    awk \'{printf "VESSEL_UPTIME=%d\\n",${\'$\'}1}\' /proc/uptime\n',
    '                    awk \'{printf "VESSEL_UPTIME=%d\\n",${\'$\'}1}\' /proc/uptime\n'
    '                    printf \'VESSEL_CPUS=\'; getconf _NPROCESSORS_ONLN 2>/dev/null || nproc 2>/dev/null || echo ${VesselRuntimeController.UML_VCPUS}\n',
)
replace_once(
    svc,
    '                var uptime = 0L\n',
    '                var uptime = 0L\n                var vcpus = VesselRuntimeController.UML_VCPUS\n',
)
replace_once(
    svc,
    '                        line.contains("VESSEL_UPTIME=") -> uptime = line.substringAfter("VESSEL_UPTIME=").trim().toLongOrNull() ?: 0\n',
    '                        line.contains("VESSEL_UPTIME=") -> uptime = line.substringAfter("VESSEL_UPTIME=").trim().toLongOrNull() ?: 0\n'
    '                        line.contains("VESSEL_CPUS=") -> vcpus = line.substringAfter("VESSEL_CPUS=").trim().toIntOrNull() ?: VesselRuntimeController.UML_VCPUS\n',
)
replace_once(
    svc,
    '                    vcpus = 1,\n',
    '                    vcpus = vcpus,\n',
)

old_catch = '''            } catch (t: Throwable) {
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
'''
new_catch = '''            } catch (t: Throwable) {
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
'''
replace_once(svc, old_catch, new_catch)

# ---------------------------------------------------------------------------
# 2. Existing persistent alpha2 images can already pass the Plasma readiness
#    check while still lacking the font package required by post-validation.
#    Make it a real runtime dependency so ensurePlasma repairs old images too.
# ---------------------------------------------------------------------------
rt = "app/src/main/java/com/example/dreamlinux/VesselRuntimeController.kt"
replace_once(
    rt,
    'qml-module-qtquick-templates2 qml-module-qtgraphicaleffects plasma-integration libkf5service-data; do ',
    'qml-module-qtquick-templates2 qml-module-qtgraphicaleffects plasma-integration libkf5service-data fonts-noto-color-emoji; do ',
)
replace_once(
    rt,
    'fonts-noto-core fonts-dejavu-core fonts-liberation firefox-esr konsole dolphin ark kcalc okular gwenview kate && ',
    'fonts-noto-core fonts-noto-color-emoji fonts-dejavu-core fonts-liberation firefox-esr konsole dolphin ark kcalc okular gwenview kate && ',
)

# ---------------------------------------------------------------------------
# 3. Stop fencing every VirGL command stream in the VM.  A context only needs
#    a host producer fence while it is attached to a resource that is actively
#    scanned out.  Track contexts dirty since the previous scanout flush and
#    wait only those.  This preserves explicit ordering while removing the
#    per-submit glFenceSync/glFlush tax from unrelated Firefox/app resources.
# ---------------------------------------------------------------------------
patch = "tools/vessel_native/patch_vhost_gpu_android_ahb.py"

replace_once(
    patch,
    'text = text.replace(resource_field, resource_field + "    pub vessel_contexts: HashSet<u32>,\\n", 1)\n',
    'text = text.replace(resource_field, resource_field + "    pub vessel_contexts: HashSet<u32>,\\n    pub vessel_dirty_contexts: HashSet<u32>,\\n", 1)\n',
)
replace_once(
    patch,
    'text = text.replace(resource_init, resource_init + "            vessel_contexts: HashSet::new(),\\n", 1)\n',
    'text = text.replace(resource_init, resource_init + "            vessel_contexts: HashSet::new(),\\n            vessel_dirty_contexts: HashSet::new(),\\n", 1)\n',
)

old_transfer = '''        self.resources
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
'''
new_transfer = '''        let scanout_write = {
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
                error!("Vessel failed to publish VirGL transfer sync ctx={ctx_id} resource={resource_id}: rc={sync_rc}");
                return Err(ErrUnspec);
            }
        }
'''
replace_once(patch, old_transfer, new_transfer)

old_submit = '''        // virglrenderer has queued this context's command stream and leaves the
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
new_submit = '''        // Only a context that can directly write an active scanout needs a
        // producer fence.  Off-screen Firefox/KWin/app resources must not force
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
'''
replace_once(patch, old_submit, new_submit)

replace_once(
    patch,
    '''        if let Some(resource) = self.resources.get_mut(&resource_id) {
            resource.vessel_contexts.remove(&ctx_id);
        }
''',
    '''        if let Some(resource) = self.resources.get_mut(&resource_id) {
            resource.vessel_contexts.remove(&ctx_id);
            resource.vessel_dirty_contexts.remove(&ctx_id);
        }
''',
)
replace_once(
    patch,
    '''        for resource in self.resources.values_mut() {
            resource.vessel_contexts.remove(&ctx_id);
        }
''',
    '''        for resource in self.resources.values_mut() {
            resource.vessel_contexts.remove(&ctx_id);
            resource.vessel_dirty_contexts.remove(&ctx_id);
        }
''',
)
replace_once(
    patch,
    '        for ctx_id in &resource.vessel_contexts {\n',
    '        for ctx_id in &resource.vessel_dirty_contexts {\n',
)
replace_once(
    patch,
    '''        }

        for scanout_id in resource.scanouts.iter_enabled() {
''',
    '''        }
        if let Some(current) = self.resources.get_mut(&resource_id) {
            for ctx_id in &resource.vessel_dirty_contexts {
                current.vessel_dirty_contexts.remove(ctx_id);
            }
        }

        for scanout_id in resource.scanouts.iter_enabled() {
''',
)
replace_once(
    patch,
    '    "vessel_contexts",\n',
    '    "vessel_contexts",\n    "vessel_dirty_contexts",\n',
)

# Tighten the native rebuild gate so CI cannot silently regress to global
# per-context fencing on later edits.
rebuild = "tools/vessel_native/rebuild_vhost_gpu_ahb.sh"
replace_once(
    rebuild,
    "grep -Fq 'vessel_contexts' \"$VHOST/vhost-device-gpu/src/backend/virgl.rs\"\n",
    "grep -Fq 'vessel_contexts' \"$VHOST/vhost-device-gpu/src/backend/virgl.rs\"\n"
    "grep -Fq 'vessel_dirty_contexts' \"$VHOST/vhost-device-gpu/src/backend/virgl.rs\"\n",
)

print("alpha3 stabilization migration applied")
