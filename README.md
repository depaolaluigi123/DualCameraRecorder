# Dual Camera Recorder

An offline, single-device Android application that records video from the **front and rear cameras simultaneously** into two separate MP4 files, with a synchronized audio track captured from the device microphone.

The app is built around the **Camera2 API** and **`MediaCodec` + `MediaMuxer`** (two H.264 encoders and one shared stereo AAC encoder). While recording it runs a foreground service, so the recording is not interrupted when the activity is in the background or the screen is off.

---

## Key features

### Simultaneous dual-camera recording
- Live preview of both cameras side-by-side (portrait) or stacked (landscape).
- Pressing **Record** gives the already open cameras a new session with their H.264 encoder: the two MP4 files in `Movies/DualCameraRecording/<timestamp>/` start at the same instant and end together.
- Many phones cannot run every front + rear combination at the same time (conflicts declared by the hardware, or configurations refused by the HAL). A pair that does not work is detected, remembered and marked in the spinners as "not usable with the other camera"; the front camera keeps previewing alone and a compatible pair is proposed at start-up.
- Touch the previews as in the camera app: a tap focuses at that point (converted to sensor coordinates: rotation, front mirroring, zoom, 4:3/16:9 framing), a two-finger pinch zooms in or out (the zoom sliders follow). A pinch never triggers a focus cycle.

### Per-camera video settings
Each camera can be configured independently:
- **Resolution** — 4:3 presets from 640x480 to 4032x3024, or 16:9 presets from 640x360 to 3840x2160 (4K) when the camera's **16:9 framing** checkbox is ticked. Each camera has its own checkbox, so one can record 4:3 and the other 16:9; the preview of each camera switches to the same framing. The checkbox reads 16:9 / 4:3 in landscape and 9:16 / 3:4 in portrait. Each shape remembers its own resolution per camera. Dimensions are swapped in portrait. Only the ones supported by the selected camera and the encoder are listed.
- **Frame rate (FPS)** — the list is read from the selected camera: only the frame rates it can hold constant at the chosen resolution are listed; the value is enforced on the sensor and the file has a constant frame rate. Some cameras run slightly faster than the selected rate (e.g. 30.2 fps): the app makes them skip a frame now and then, so the video stays in sync with the audio (with the app in the background the extra frames are kept instead, slightly off the constant grid but still in sync).
- **Bitrate** — the encoder runs in CBR and the stream is topped up with H.264 filler data when the scene is too simple, so the file bitrate matches the selected one. "Auto" is computed from resolution and FPS and shown in its label. Presets go up to 500 Mbps, but only the values up to the encoder's maximum are listed (100 Mbps on the tested phone). At very high resolutions the encoder may not keep up with the highest bitrates in real time and the frame rate drops (tested phone: 2592x1944 at 30 fps holds 60 Mbps, not 80–100).

### Manual camera controls (independent per camera)
- **Manual focus** (only on cameras that can focus; on fixed-focus lenses the checkbox is dimmed and says why) with a focus-distance seekbar (covering the whole lens range, from infinity to the closest focus distance) and ± buttons; tap-to-focus re-applies the current distance when manual focus is enabled. It is disabled on fixed-focus cameras.
- **Manual ISO** and **exposure time** spinners (separate for front and rear).
- **Flash / torch** control on the rear camera, usable together with tap-to-focus (the torch state is part of every capture request, the autofocus ones included), with proper handling of logical multi-cameras (the torch is routed to the physical sub-camera that owns the flash unit).

### Audio capture and metering
- Audio is captured **once, in stereo** (the same capture that drives the meters) and encoded as **stereo AAC-LC**; the same track is written into both MP4 files.
- Configurable AAC bitrate and sample rate: only the bitrates AAC can really produce at the selected sample rate are listed.
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
- Resolution, FPS, bitrate, audio settings, theme, language and meter style are persisted (SharedPreferences) and restored across app restarts; the camera selection is not persisted.
- Back-button and exit confirmations prevent accidental interruption; while a recording is in progress, the back button is blocked, the orientation controls are locked and theme / language cannot be changed.

### Reliability features
- While recording, a foreground service (type `camera | microphone`) keeps camera and microphone access when the activity is not visible, and the screen stays on. When not recording, camera and microphone are released in the background and re-opened on return.
- Re-entrancy guards around camera start/stop prevent the surface-texture and global-layout listeners from racing each other and triggering `ERROR_CAMERA_IN_USE`.
- Pending camera changes are queued and applied after the in-flight start completes.
- The recording starts when both cameras deliver frames: the two files begin at the same instant (same timeline, same audio track). If one camera does not start, the other one still records and the user is told; if none starts, the UI returns to idle with an error message.

---

## Output

Each recording creates a folder inside the device's `Movies/DualCameraRecording/` directory:

```
Movies/DualCameraRecording/
  2025_09_01_14_22_08/
    front.mp4
    rear.mp4
```

Both files contain the same stereo AAC audio track, encoded once from the same microphone capture.

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
- **`MediaCodec` + `MediaMuxer`** for encoding (H.264 CBR, stereo AAC-LC)
- **ViewBinding**, Material Components, ConstraintLayout
- **SharedPreferences** for persisting settings
- **Foreground service** with type `camera | microphone`, running while recording

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
│   ├── camera/                          # Camera2: dual recorder, per-camera controllers, capabilities
│   ├── data/                            # Preferences repository (SharedPreferences)
│   ├── locale/                          # Locale manager
│   ├── model/                           # Configuration models
│   ├── recording/                       # H.264/AAC encoders, MP4 muxer, recording session
│   ├── service/                         # Foreground service while recording
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

Dual Camera Recorder is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License v3.0 (GPL-3.0).



