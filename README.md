# Dual Camera Recorder

An offline, single-device Android application that records video from the **front and rear cameras simultaneously** into two separate MP4 files, with a synchronized audio track captured from the device microphone.

The app is built around the **Camera2 API** and **`MediaRecorder`** and runs as a foreground service so that recording is not interrupted when the activity is in the background or the screen is off.

---

## Key features

### Simultaneous dual-camera recording
- Live preview of both cameras side-by-side (portrait) or stacked (landscape).
- Pressing **Record** starts two `MediaRecorder` sessions in lock-step, each writing to its own MP4 file inside `DCIM/DualCameraRecording/<timestamp>/`.
- The cameras are picked from the device's reported `concurrentCameraIds` so the pair is guaranteed to run together. If the desired combination is not supported, the app automatically falls back to a working pair and notifies the user.
- Tap-to-focus is available on every preview surface.

### Per-camera video settings
Each camera can be configured independently:
- **Resolution** — multiple presets, automatically swapped between 3:4 (portrait) and 4:3 (landscape) when the orientation changes.
- **Frame rate (FPS)** — the value is requested to the sensor; the camera picks the nearest supported value.
- **Bitrate** — several bitrate tiers to balance file size and quality.

### Manual camera controls (independent per camera)
- **Manual focus** with a focus-distance seekbar and ± buttons; tap-to-focus re-applies the current distance when manual focus is enabled.
- **Manual ISO** and **exposure time** spinners (separate for front and rear).
- **Flash / torch** control on the rear camera, with proper handling of logical multi-cameras (the torch is routed to the physical sub-camera that owns the flash unit).

### Audio capture and metering
- Audio is captured in **AAC** inside the same MP4 container as the video.
- Configurable AAC bitrate and sample rate.
- A live audio meter is always on (when microphone permission is granted), with two visual styles:
  - **Digital (DAW)**
  - **Analog (tape style)**
- The meter drives both the main activity and the fullscreen overlay, with one channel per camera.

### User experience
- **Fullscreen preview** mode with both cameras side-by-side, a toggle to hide the controls, and a back button to return to the main view.
- **Portrait / Landscape** orientation toggle.
- **Light / Dark** theme.
- **English / Italian** language.
- A **device-compatibility alert** is shown on first launch and can be dismissed permanently with a "Don't show again" checkbox.
- All user choices (camera pair, resolution, FPS, bitrate, focus, ISO, exposure, flash, theme, language, meter style) are persisted via DataStore and restored across app restarts.
- Back-button and exit confirmations prevent accidental interruption; while a recording is in progress, the back button is blocked and the orientation controls are locked.

### Reliability features
- A foreground service keeps the camera sessions alive when the activity is not visible.
- Re-entrancy guards around camera start/stop prevent the surface-texture and global-layout listeners from racing each other and triggering `ERROR_CAMERA_IN_USE`.
- Pending camera changes are queued and applied after the in-flight start completes.
- The recorder uses a robust start order (prepare both `MediaRecorder`s, open the cameras with preview + record surfaces already in the session, wait for both sessions to be configured, only then call `MediaRecorder.start()`) to avoid the Camera2/MediaRecorder contract being violated.

---

## Output

Each recording creates a folder inside the device's `DCIM/DualCameraRecording/` directory:

```
DCIM/DualCameraRecording/
  2025-09-01_14-22-08/
    front.mp4
    rear.mp4
```

The two files share a synchronized AAC audio track (one track per file, both captured from the same microphone capture session).

---

## Requirements

- **Android 8.0 (API 26)** or later
- A device with **at least two physical cameras** (front and rear)
- A microphone (built-in is sufficient)
- Permissions requested at runtime:
  - `CAMERA`
  - `RECORD_AUDIO`
  - `POST_NOTIFICATIONS` (Android 13+)
  - `WRITE_EXTERNAL_STORAGE` / `READ_EXTERNAL_STORAGE` (legacy)

---

## Tech stack

- **Kotlin 2.1.0** with **Coroutines**
- **Android Gradle Plugin 8.7.3**, `compileSdk = 34`, `minSdk = 26`, `targetSdk = 34`
- **Java 17** / **JVM target 17**
- **Camera2 API** for camera control
- **`MediaRecorder`** for encoding
- **ViewBinding**, Material Components, ConstraintLayout
- **DataStore (Preferences)** for persisting settings
- **Foreground service** with type `camera | microphone` for background recording

---

## Build

```bash
./gradlew assembleDebug
```

The `release` build is unsigned by default; configure your own signing config in `app/build.gradle.kts` before publishing.

---

## Project structure

```
app/src/main/
├── java/com/dualcamerarecording/
│   ├── MainActivity.kt                  # UI, camera UI orchestration, settings UI
│   ├── MainViewModel.kt
│   ├── DualCameraRecorderApp.kt         # Application class, owns the MicStateStore
│   ├── audio/                           # Microphone capture, gain math, state store
│   ├── camera/                          # Camera2 + MediaRecorder wrapper, per-camera controllers
│   ├── data/                            # DataStore-backed preferences repository
│   ├── locale/                          # Locale manager
│   ├── model/                           # Configuration models
│   ├── service/                         # Foreground recording service
│   ├── settings/                        # Reactive camera settings store
│   ├── theme/                           # Light/Dark theme manager
│   └── ui/                              # Settings bottom sheet, dialogs, focus reticle
└── res/
    ├── layout/                          # Main activity + bottom sheet + dialogs
    ├── values/                          # English strings, colors, themes
    ├── values-it/                       # Italian strings
    ├── values-night/                    # Dark-theme colors
    └── drawable/                        # Vector icons
```

---

## Notes on device compatibility

Because the app drives two cameras at the same time and uses the hardware encoder directly, behavior varies from device to device. Supported resolutions, frame rates and audio settings depend on the device's hardware. On some phones the dual-camera combination may not work, the preview may freeze, the recording may fail, or the audio may be missing. There are also hardware limits (number of cameras that can run concurrently, max resolution per camera, max audio sample rate) that the app cannot bypass. If something does not work as expected, try changing the front/rear camera, the resolution or the FPS — the device-compatibility alert that appears on first launch explains this in more detail.


---

## License

Copyright (C) 2026 Luigi De Paola

Volume Power App is free software: you can redistribute it and/or modify it under the
terms of the GNU General Public License v3.0 (GPL-3.0).



