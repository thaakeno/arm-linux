# Agent Build & Artifact Workflow

This document provides exact instructions for autonomous agents (e.g. ChatGPT, Claude, Antigravity) to trigger builds, retrieve artifacts, inspect verification status, and preserve repository storage.

## Zero-Quota Storage Architecture

Standard GitHub Actions artifacts accumulate across builds and consume account storage quotas. This pipeline bypasses artifact storage bloat using a two-tier strategy:

1. **Rolling GitHub Release (`latest`)**: The build publishes `app-debug.apk` and `verification.json` directly to a pre-release tagged `latest` using `gh release upload latest ... --clobber`. Release assets do not count against the GitHub Actions storage quota, and `--clobber` overwrites the files on every build, maintaining exactly one set of assets in storage at all times.
2. **Static Direct Download URLs**: Because release assets have predictable endpoints, agents do not need to parse GitHub Actions run logs, extract `.zip` bundles, or authenticate with personal access tokens to fetch the APK or diagnostics.
3. **1-Day Auto-Expiry Fallback**: Any auxiliary artifact uploaded to the Actions run is capped with `retention-days: 1` so that it automatically expires within 24 hours.
4. **Concurrency Control**: Rapid successive commits automatically cancel earlier queued or in-progress runs, preventing unnecessary runner minute consumption.

## Direct Download Endpoints

Agents or curl scripts can fetch the latest compiled debug APK and verification metadata directly:

```bash
# Latest compiled APK
curl -L -o app-debug.apk "https://github.com/thaakeno/arm-linux/releases/download/latest/app-debug.apk"

# Latest verification status JSON
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
Pushing commits to the `master` or `main` branch automatically starts the `Build APK` workflow.

### Manual Dispatch (CLI / API)
To trigger a build without modifying code:

```bash
gh workflow run build.yml --repo thaakeno/arm-linux
```

To monitor the run:

```bash
gh run list --workflow=build.yml --repo thaakeno/arm-linux
gh run watch --repo thaakeno/arm-linux
```

## On-Device Deployment

Once the workflow finishes and the APK is downloaded:

```bash
adb install -r app-debug.apk
adb shell am start -n com.example.dreamlinux/.MainActivity
```
