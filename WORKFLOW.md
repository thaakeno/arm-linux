# Agent Build & Artifact Workflow

This document provides exact instructions for autonomous agents (e.g. ChatGPT, Claude, Antigravity) working on `arm-linux`.

## Multi-Agent Branch & Release Isolation

Two agents work simultaneously on separate development tracks:
- **Agent 1**: Works strictly on branch `dev-1`. Pushes trigger the `latest-dev-1` release.
- **Agent 2**: Works strictly on branch `dev-2`. Pushes trigger the `latest-dev-2` release.
- **Master**: Neither agent pushes directly to `master`. After verification, the winning implementation is merged into `master`, triggering the production `latest` release.

## Zero-Quota Storage Architecture

Standard GitHub Actions artifacts accumulate across builds and consume account storage quotas. This pipeline bypasses artifact storage bloat using a two-tier strategy:

1. **Branch-Isolated Rolling Releases**: Every push to `dev-1`, `dev-2`, or `master` publishes `app-debug.apk` and `verification.json` to a dedicated rolling pre-release tag (`latest-dev-1`, `latest-dev-2`, or `latest`) using `gh release upload ... --clobber`. Release assets do not count against the GitHub Actions storage quota, and `--clobber` overwrites the files in place, ensuring each branch never stores more than one single APK.
2. **Static Direct Download URLs**: Endpoints are deterministic and unauthenticated. Agents do not need to parse run logs, extract `.zip` archives, or supply tokens.
3. **1-Day Auto-Expiry Fallback**: Run artifacts are capped with `retention-days: 1` so they vanish within 24 hours.
4. **Concurrency Control**: Rapid commits on the same branch automatically cancel earlier queued or running jobs.

## Direct Download Endpoints

### Branch `dev-1`
```bash
# APK
curl -L -o app-debug-dev1.apk "https://github.com/thaakeno/arm-linux/releases/download/latest-dev-1/app-debug.apk"
# Verification status
curl -L -o verification-dev1.json "https://github.com/thaakeno/arm-linux/releases/download/latest-dev-1/verification.json"
```

### Branch `dev-2`
```bash
# APK
curl -L -o app-debug-dev2.apk "https://github.com/thaakeno/arm-linux/releases/download/latest-dev-2/app-debug.apk"
# Verification status
curl -L -o verification-dev2.json "https://github.com/thaakeno/arm-linux/releases/download/latest-dev-2/verification.json"
```

### Branch `master`
```bash
# APK
curl -L -o app-debug.apk "https://github.com/thaakeno/arm-linux/releases/download/latest/app-debug.apk"
# Verification status
curl -L -o verification.json "https://github.com/thaakeno/arm-linux/releases/download/latest/verification.json"
```

## Verification Matrix Schema

The published `verification.json` records what each build actually verified:

```json
{
  "commit": "<git-sha>",
  "avf_api_path": "android.system.virtualmachine.VirtualMachineManager",
  "vm_creation": "PASS",
  "vm_boot": "PASS",
  "connect_vsock": "PASS",
  "guest_command": "PASS",
  "debian": "NOT TESTED",
  "gles": "NOT TESTED"
}
```

## Triggering Builds

### Automatic Trigger
Pushing commits to `dev-1`, `dev-2`, or `master` automatically starts the build.

### Manual Dispatch (CLI / API)
```bash
gh workflow run build.yml --ref dev-1 --repo thaakeno/arm-linux
gh workflow run build.yml --ref dev-2 --repo thaakeno/arm-linux
```

## Local dev-1 repair checkpoint

Work stays on `dev-1`. Do not push or trigger publishing until the user requests it.
Run `./gradlew stageDebugApk testDebugUnitTest lintDebug`. The named APK is under
`build/deliverables/DreamLinux-0.4.0-dev1-<commit>[-dirty]-arm64.apk`.
The About screen and exported diagnostics identify version name, monotonically
increasing dev-1 commit-count-based version code, branch and source commit.
Versions are development identifiers, not claims of a functioning Debian desktop.
Commit-count codes assume this branch is not rebased or rewritten; use an explicit
release version strategy before merging divergent branches.

CI checks the build, unit tests, lint and APK v3 signature. Full checkout history is
required for version codes. The fixed `app-debug.apk` download remains a compatibility
alias; a versioned APK is also published. Add repository secret
`DEBUG_KEYSTORE_BASE64` containing the existing trusted development debug keystore
(alias `androiddebugkey`, standard Android debug passwords) to retain update identity.
Never commit this key. Without that secret CI builds but does not publish a rolling
release with a different signing identity. A key cannot be recovered from an APK;
previous ephemeral CI keys may already prevent in-place updates. Do not uninstall
or erase phone data to hide a signing mismatch.

Runtime acceptance remains separate: managed guest command, stop/start reconnect,
Debian serial boot, Wayland frame output and measured GPU rendering all need the
phone. A successful build, API availability or STATUS_RUNNING is not a runtime PASS.
Protected boot trust is not changed by setting `protectedVm=true`. The ordinary
protected-VM network adapter is disabled; host-mediated networking is not implemented.
The current code requests system GfxStream integration; no independent GfxStream
renderer or cross-VM graphics transport has been implemented in this checkpoint.
