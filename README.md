# CloniX

[![CI](https://github.com/txorav/CloniX/actions/workflows/build.yml/badge.svg)](https://github.com/txorav/CloniX/actions/workflows/build.yml)

**True multi-instance app cloning for Android 9+ (API 28 – 36).** CloniX (the CloneForge engine, GPL-3.0)
is an open-source Android application that clones installed apps into *real* APK installs — every clone gets its own
Linux UID, SELinux domain, storage sandbox, runtime permissions, and launcher icon — powered by
static binary package mutation and an injected in-process compatibility runtime.

CloniX avoids the fatal on modern Android (12–16) limits of legacy virtual-container cloners
(VirtualApp-style Binder UID mismatches, phantom process killing, keystore2 alias collisions,
shared storage): each clone is a **first-class citizen of the OS itself**.

## How it works

### Builder pipeline (runs inside CloniX)
1. **Ingest** the installed app (base + split APKs), extract the original X.509 certificate and
   the raw V2/V3 APK Signing Block.
2. **Mutate the binary AndroidManifest.xml** with [ARSCLib]: rename the package
   (`com.target.app → com.target.app.cf001`), expand relative class names, rewrite
   process/taskAffinity prefixes, re-authority every `<provider>`
   (`authority → authority.cf001`), remap declared permissions, strip `sharedUserId`, and record
   the original `Application`/`AppComponentFactory` in injected `<meta-data>`.
3. **Patch `resources.arsc`**: rewrite `app_name` per locale and recolor launcher icons
   (HSL hue shift) so clones are visually distinguishable.
4. **Inject the compatibility runtime**: append `runtime-payload.dex` as `classes<N+1>.dex`,
   pack the native hook engine (`lib/<abi>/libcloneforge_core.so`, 16 KB-aligned), and embed
   `assets/cloneforge/{config.json, origin_cert.der, origin_apk.bin}`.
5. **Align & sign** (V1 + V2 + V3 schemes, 16 KB page-size alignment for Android 15/16) with a
   per-device persistent key so clone updates install in place.
6. **Install** atomically via `android.content.pm.PackageInstaller` sessions (multi-APK safe).

### Runtime engine (runs inside the clone)
The payload dex executes before `Application.attachBaseContext()` via `AppComponentFactory`
(API 28+) and installs:

| Subsystem | Purpose |
|---|---|
| `SignatureSpoofHook` | `IPackageManager` proxy — clone sees the **original app's** certificates, install source shows Play Store |
| `IntentRouterHook` | explicit `ComponentName`/`setPackage(orig)` traffic is re-routed inside the clone; Android 14+ receiver export flags auto-fixed |
| `ProviderUriHook` | `content://` authority translation table so providers/FileProvider survive re-authority |
| `PackageNameRouter` | stack-aware `getPackageName()` — system Binder calls get the clone package, app-level checks get the original |
| `DeviceIdentityHook` | per-clone persistent synthetic `ANDROID_ID`, Widevine ID, GAID, IMEI, ICCID, MAC |
| `AccountManagerHook` | isolates `AccountAuthenticator` & `SyncAdapter` XMLs and proxies AccountManager/ContentResolver queries without system account collisions |
| Native `libcloneforge_core.so` | `openat/faccessat/fstatat64` (PLT/Symbol hook via ShadowHook): pristine original APK served to integrity checkers, hardcoded `/data/data/<orig>` paths redirected, `/proc/self/maps` scrubbed through a filtered `memfd` |

## Modules

```
app/             CloniX UI (Material 3): app picker, clone options, history, installer flow
patcher/         Mirror binary engine: ingest, AXML/ARSC mutation, dex+libs injection, signing, installer
runtime-payload/ Pure-Java payload dex injected into clones (CFComponentFactory, hooks)
native-bridge/   NDK hook engine (ShadowHook), 16 KB page-aligned, arm64-v7a/arm64-v8a
ci/prepare_payload.sh   CI step: dexifies the payload AAR and packs assets before the app build
```

## Build

Releases are built automatically by GitHub Actions (`.github/workflows/build.yml`) on every push
and tag (`v*` creates a GitHub release with the signed APK). To build locally:

```bash
# 1. Build the runtime payload + native bridge AARs
gradle :runtime-payload:assembleRelease :native-bridge:assembleRelease

# 2. Pack the payload dex + native libs into app assets
ci/prepare_payload.sh        # requires ANDROID_HOME with platforms + build-tools

# 3. Build the app
gradle :app:assembleRelease
```

Release signing already works out of the box: CI resolves repository secrets
`CF_KEYSTORE_BASE64`, `CF_KEYSTORE_PASS`, `CF_KEY_ALIAS`, `CF_KEY_PASS` (the project's stable
RSA-2048 key, so successive release APKs upgrade in place without "signature mismatch").
If no secrets are set, CI falls back to an ephemeral keystore (fresh key per run). To use your
own key, generate one and set the four secrets yourself:

```bash
keytool -genkeypair -v -keystore my.rks -alias cloneforge -keyalg RSA -keysize 2048 \
  -validity 10000 -dname "CN=CloniX Release"
base64 -w0 my.rks | gh secret set CF_KEYSTORE_BASE64 -R OWNER/CloniX --body "$(cat -)"
gh secret set CF_KEYSTORE_PASS -R OWNER/CloniX
gh secret set CF_KEY_ALIAS   -R OWNER/CloniX
gh secret set CF_KEY_PASS    -R OWNER/CloniX
```

## Install requirements
- Android 9.0+ (API 28)
- Grant **"Install unknown apps"** for CloniX (standard `REQUEST_INSTALL_PACKAGES` flow).
- arm64-v8a / armeabi-v7a devices (the native engine only hooks thumb/arm32 + arm64).

## Roadmap
- ✅ LSPlant-based ART hooks (package router, device-identity spoofing, ContentResolver/FileProvider
  interception) — active since v1.1.0 via the shadowhook-backed C++ bridge in `native-bridge`.
- ✅ Account-authenticator & SyncAdapter XML rewriting and runtime virtualization (spec §8).
- ✅ Shizuku/ADB silent installer bridge (`USER_ACTION_NOT_REQUIRED` without being a device owner; streamed stdin session pipelining via `pm install-write -S`).

## Language & contributing
PRs welcome. For design changes, please open an issue first so the architecture direction is
discussed before implementation.

## Legal / ethical notice
CloniX is a technical research tool for legitimate multi-account use of apps **you are
entitled to use** — a personal productivity need, QA testing, accessibility, or recovering from
vendor account limits, for example. You are responsible for complying with the terms of service
of the apps you clone, applicable law, and intellectual property. The tool does not distribute
copyrighted applications and does not assist with piracy, license bypass, cheating, or fraud.
The project is distributed under **GPL-3.0**.
