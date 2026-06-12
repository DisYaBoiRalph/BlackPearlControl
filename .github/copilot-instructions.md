# Copilot instructions for BlackPearlControl

## Build, test, and lint commands

Use Gradle from the repository root (`.\gradlew.bat` on Windows, `./gradlew` on Unix-like shells).

- Build debug APK: `.\gradlew.bat assembleDebug`
- Run all JVM unit tests (app + shared): `.\gradlew.bat testDebugUnitTest`
- Run Android lint for debug: `.\gradlew.bat lintDebug`
- Run instrumented tests on a connected device/emulator: `.\gradlew.bat connectedDebugAndroidTest`

Single-test examples:

- App module single class: `.\gradlew.bat :app:testDebugUnitTest --tests "com.fossyaudio.bpcontrol.transport.protocol.BlackPearlCodecTest"`
- Shared module single class: `.\gradlew.bat :shared:testDebugUnitTest --tests "com.fossyaudio.bpcontrol.shared.eq.BiquadMathTest"`

## High-level architecture

This project is a two-module Android/Kotlin codebase:

- `:app` is the Android UI + USB transport integration.
- `:shared` holds portable DSP/math and preset-identification logic used by the app.

Main runtime flow:

1. `MainActivity` owns app lifecycle, USB attach/permission handling, settings synchronization, and EQ/preset UI state.
2. USB writes are funneled through `UsbCommandQueueProcessor`, which serializes HID control transfers and applies command-specific pacing/retries.
3. Protocol constants and payload framing live in `BlackPearlProtocol` + `BlackPearlCodec`; settings readback parsing is centralized in `DacSettingsMapper`.
4. EQ DSP is computed via `shared.eq.BiquadMath`, while preset matching is handled by `shared.preset.PresetMatcher`.
5. Presets persist through `PresetRepository` (SharedPreferences JSON), with app-level DI via `AppContainer`.

## Key repository conventions

- **CB-first protocol behavior for Black Pearl**: The code treats SchemeNo 16 hardware as CB-profile by default, while keeping a LEGACY mapping path for compatibility (`BlackPearlProtocol.FirmwareProfile` and profile-aware type mapping/parsing).
- **Do not bypass the queue for writes**: HID commands should go through `sendHidCommand()` -> `UsbCommandQueueProcessor.enqueue()` to preserve ordering, retries, and timing behavior.
- **Keep USB timing/config in protocol constants**: Use `BlackPearlProtocol.Timing` values rather than new hardcoded delays/timeouts.
- **PEQ disable semantics are gain-zero based**: disabled bands are encoded with effective gain `0f`; parser and preset matching treat near-zero gain as neutral (`PK`) behavior.
- **`activeSlot` matters for persistence**: PEQ readback hydration captures `activeSlot`; flash/latch workflows rely on it and log when unresolved.
- **System presets are mandatory**: `PresetRepository` always ensures `"Flat"` and `"None"` exist; `"None"` is used as the mutable snapshot when hardware state does not match a named preset.
- **Graph rendering expects immutable snapshots**: UI code passes copied band lists (`eqBands.map { it.copy() }`) into `EqGraphView` to avoid concurrent mutation artifacts.
