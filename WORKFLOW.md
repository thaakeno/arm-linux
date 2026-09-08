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
