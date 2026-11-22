# Project Context: Unlimited Cloud (Kotlin Multiplatform + TDLib)

## 1. Architecture Overview
This is a Kotlin Multiplatform (KMP) & Compose Multiplatform project targeting **Android** and **Desktop (JVM)** using Telegram as an unlimited cloud storage backend via **TDLib**.

- `androidApp/`: Thin `com.android.application` shell (manifest + packaging only). AGP 9 forbids applying `com.android.application` to a Kotlin Multiplatform module, so the APK is assembled here from `:composeApp`.
- `composeApp/src/commonMain/`: Shared UI (Compose), Domain models, State management, SQLDelight database, and `expect` declarations for TelegramClient & FilePicker.
- `composeApp/src/androidMain/`: Android-specific `actual` implementations (TDLib JNI `.so` bindings, Android context paths, FilePicker). Android reaches TDLib's JSON API reflectively through `org.drinkless.tdlib.Client` until the native binaries are bundled.
- `composeApp/src/desktopMain/`: Desktop JVM `actual` implementations (TDLib JSON transport through JNA + `libtdjson`, Desktop paths, FilePicker).

## 2. CRITICAL Environment Rules (Termux / Mobile Dev)
- **DO NOT run `./gradlew`, `gradle`, or `adb` locally.** This workspace is running on an Android device (Termux) without Android SDK or sufficient RAM for JVM/Gradle builds.
- Always run `python3 scripts/preflight.py` before committing any changes.
- After pushing changes (`git push`), monitor the remote build using `gh run watch --exit-status`. If it fails, inspect logs with `gh run view --log-failed` and patch the code.

## 3. Coding Conventions- Never import `android.*`, `java.io.File`, or `javax.*` inside `commonMain`. Use `okio.Path` / `okio.FileSystem` for multiplatform file handling.
- Wrap all TDLib callbacks into Kotlin `Flow` or `suspendCancellableCoroutine`.
- Keep UI components modular and responsive for both mobile portrait and desktop window layouts.
