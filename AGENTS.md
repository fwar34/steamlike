# AGENTS.md

## What this is

Kotlin Android app + C Windows companion program. The Android app captures gamepad input via a transparent overlay window, maps it through a "public layer + 10 operation layers" system (inspired by Steam Input), and sends mapped events over TCP (port 27015) to a Windows client that injects them via `SendInput()`. Designed for WoW Turtle 1.18.1 running in Winlator.

## Build

```bash
# Full APK build (compiles Windows exe automatically if gcc available)
./gradlew assembleDebug

# APK output
app/build/outputs/apk/debug/app-debug.apk
```

### Windows exe (auto-compiled by Gradle)

The `compileWindowsExe` task runs before `preBuild`. It searches for gcc in order:
1. `M:/msys64/ucrt64/bin/gcc.exe`
2. `C:/msys64/ucrt64/bin/gcc.exe`
3. `C:/MinGW/bin/gcc.exe`
4. System PATH

If gcc is not found, compilation is skipped silently (uses existing `app/src/main/assets/inputbridge_client.exe`). The compiled exe is always copied to `app/src/main/assets/inputbridge_client.exe`, and `windows/control.bat` / `windows/control.ps1` are synced to assets too.

To compile manually outside Gradle:
```bash
cd windows
gcc -O2 -o inputbridge_client.exe inputbridge_client.c -lws2_32 -luser32
```

## Tests

```bash
./gradlew test
```

Unit tests are pure JVM (no device needed). Test files are under `app/src/test/`. They use integer constants instead of Android `KeyEvent` constants for portability.

## Project structure

- `app/src/main/java/com/steamlike/controller/` — all Kotlin source
  - `core/` — `SteamInput` (main controller), `MappingTypes` (data structures), `ControllerTypes` (enums), `ControllerDevice`
  - `injection/` — `GamepadInputView` (overlay), `InputBridgeServer` (TCP), `BridgeInputInjector` (VK mapping)
  - `mapping/` — `KeyboardMouseMapper`, `WoWActionSets` (layer presets, `LAYER_NAMES`)
  - `config/` — `ConfigManager`, `ControllerConfig` (JSON serialization), `AppConfig`
  - `service/` — `ControllerOverlayService` (foreground service, the main orchestrator)
  - `ui/` — `UiKit`
  - `MainActivity.kt`, `LayerEditActivity.kt`, `GamepadTestActivity.kt`
- `windows/` — C source and build scripts for the Windows companion
- `windows/inputbridge_client.c` — the Windows client (TCP recv → SendInput)

## Key architecture facts

- **Layer switching is driven by `SwitchLayer` mappings in the public layer**, not by `OperationLayer.triggerButton`. The `triggerButton` field is UI-only.
- **Key resolution order**: active layers (in activation order) → public layer fallback.
- **`ControllerOverlayService`** is the central orchestrator: creates `SteamInput`, `BridgeInputInjector`, `InputBridgeServer`, `KeyboardMouseMapper`, and the overlay views.
- **`LayerEditActivity.steamInputRef`** is a static reference set by the service — the settings UI depends on the service running.
- **LayerEditActivity pauses/resumes the overlay** on create/destroy (via Intent actions) to avoid blocking Android back gestures.
- Config is persisted as version=2 JSON at `{internal storage}/files/steamlike_config.json`.

## Gotchas

- **Proxy in `gradle.properties`**: HTTP/HTTPS proxy is hardcoded to `127.0.0.1:7897`. Remove or comment out if building without a local proxy.
- **Aliyun + Tencent mirrors**: `settings.gradle.kts` uses Aliyun mirrors; `gradle-wrapper.properties` uses Tencent mirror for Gradle 9.5.0. These may need changing outside China.
- **No instrumented tests**: only JUnit 4 unit tests exist. There are no `androidTest/` files.
- **GCC is optional**: the APK builds without GCC. The bundled exe in assets is used as fallback.
- **`val` immutability on `OperationLayer`**: `name` and `triggerButton` are `val`. Use `copy()` to modify them (see `LayerEditActivity`).
- **Single-process Windows client**: uses named mutex `Global\SteamLikeInputBridgeClient`.
