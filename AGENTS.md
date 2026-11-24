# Project Context: Unlimited Cloud (Kotlin Multiplatform + TDLib)

## 1. Architecture Overview

This is a Kotlin Multiplatform (KMP) & Compose Multiplatform project targeting
**Android** and **Desktop (JVM)** using Telegram as an unlimited cloud storage
backend via **TDLib**.

- `androidApp/`: Thin `com.android.application` shell (manifest + packaging only).
  AGP 9 forbids applying `com.android.application` to a Kotlin Multiplatform
  module, so the APK is assembled here from `:composeApp`.
- `composeApp/src/commonMain/`: Shared UI (Compose), domain models, state
  management, and `expect` declarations for the platform APIs. Generated sources
  (`AppBuildInfo`, `TelegramCredentials`,
  `ChangelogData`) land in `build/generated/…` and are never committed.
- `composeApp/src/androidMain/`: Android `actual` implementations (TDLib JNI
  through JNA, Android paths, SAF file picker, gallery scan, WorkManager
  background sync, MediaStore photo saver, FileProvider share, battery settings,
  back handler). `libtdjson.so` per ABI must sit in
  `androidApp/src/main/jniLibs/<abi>/` (downloaded by
  `scripts/fetch-tdlib-android.sh`, ignored by Git).
- `composeApp/src/desktopMain/`: Desktop JVM `actual` implementations (TDLib JSON
  transport through JNA + `libtdjson`, Desktop paths, `JFileChooser`, clipboard
  share, window entry point). The deb/rpm/msi bundle `libtdjson` from
  `src/desktopMain/resources/`, populated by `scripts/fetch-tdlib-desktop.sh`
  (ignored by Git).

## 2. CRITICAL Environment Rules (Termux / Mobile Dev)

- **DO NOT run `./gradlew`, `gradle`, or `adb` locally.** This workspace may run on
  an Android device (Termux) without Android SDK or sufficient RAM for
  JVM/Gradle builds.
- Always run `python3 scripts/preflight.py` and
  `python3 scripts/check-versions.py` before committing.
- After pushing changes (`git push`), monitor the remote build using
  `gh run watch --exit-status`. If it fails, inspect logs with
  `gh run view --log-failed` and patch the code.

## 3. Coding Conventions

- Never import `android.*`, `java.io.File`, or `javax.*` inside `commonMain`.
  Use `okio.Path` / `okio.FileSystem` for multiplatform file handling.
- Add a platform capability as a small `expect`/`interface` (or a Compose
  `remember…` API) and implement it in both `androidMain` and `desktopMain`;
  desktop must degrade explicitly (`supported = false` or no-op).
- Wrap all TDLib callbacks into Kotlin `Flow` or `suspendCancellableCoroutine`.
- Keep UI components modular and responsive for both mobile portrait and desktop
  window layouts. The rail/bottom-bar breakpoint is `920.dp`.
- Preserve the `inputMessageDocument` → `inputDocument` → `inputFileLocal` wrapper
  in `CloudFileRepository`; using `InputFile` directly breaks uploads.
- Do not hardcode the app version. Read `com.unlimitedcloud.AppBuildInfo`.

## 4. Navigation Contract

`SignedInScreen` models every page as `AppDestination` (`Section`, `Diagnostics`,
`Changelog`) and renders them through one `AnimatedContent`. When adding a page:

1. Add a destination (not a boolean overlay beside the sections).
2. Store the selected section with `rememberSaveable`.
3. Route tab selection through `selectSection`, which clears any overlay.
4. Provide a "Kembali" control; `PlatformBackHandler` handles Android back.

Keep the same transition spec for every destination so menu movement stays
consistent.

## 5. Versioning and Changelog

- `gradle.properties` → `unlimitedcloud.versionName` is the only version source.
  `versionCode` is derived as `major*10000 + minor*100 + patch`.
- `CHANGELOG.md` is bundled by `generateChangelog` and drives the in-app changelog
  and the post-update dialog. Edit it for every user-visible change.
- Bump with `python3 scripts/bump-version.py <version>`; never edit version strings
  in Kotlin or `androidApp/build.gradle.kts` by hand.
- CI runs `scripts/check-versions.py`; a mismatch between the top changelog entry
  and `gradle.properties` fails the build.

## 6. Policies to Preserve

- No storage permissions (SAF only), including gallery sync.
- Device gallery writes use MediaStore (`Pictures/Unlimited Cloud`) on Android 10+;
  never add `WRITE_EXTERNAL_STORAGE` or `READ_MEDIA_IMAGES` for them.
- Background gallery sync is opt-in through WorkManager (network + battery
  constraints, small batch, no foreground service, no notification).
- No `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; the battery menu only opens system
  settings and OEM autostart shortcuts.
- Share only through the FileProvider cache (`cache/shared/`).
- Never log phone numbers, codes, passwords, or message payloads.

## 7. Documentation

- `README.md` — overview, features, quick start, versioning.
- `docs/ARCHITECTURE.md` — modules, data flow, platform APIs, generated code.
- `docs/BUILDING.md` — setup, secrets, TDLib natives, signing, troubleshooting.
- `docs/RELEASING.md` — bump/changelog/tag/Play checklist.
- `docs/PRIVACY.md` — data handling and Play compliance.
- `CONTRIBUTING.md` — workflow and review expectations.

Update the relevant document when behavior, permissions, build steps, or the
release process changes.
