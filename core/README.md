# LightPilot Core

This directory contains the Android-independent Kotlin logic for member C.

The core module intentionally does not depend on:

- Android SDK types
- Insta360 SDK types
- Compose
- HTTP clients
- API keys

Current flow:

```text
FrameAnalyzer
  -> VisionMetrics
UserIntent + VisionMetrics + SceneSemantic + CameraCapabilities
  -> PolicyEngine
  -> TemporalController
  -> SafetyGuard
  -> PolicyProposal / SafetyDecision
```

This source tree is now the independent Gradle module `:core`. The Android
application depends on it with `implementation(project(":core"))`.

The tests use `kotlin.test` and can be run with:

```powershell
.\gradlew.bat :core:test
```

The module targets JVM 11 so it can be consumed by the Android app while the
Gradle daemon continues to run on the Android Studio JDK 25.

## P0 behavior

The current policy engine only executes the Auto-mode EV path:

```text
HOLD
EV_ONE_STEP_UP
EV_ONE_STEP_DOWN
```

An EV target must be the adjacent value in the camera-provided
`supportedEv` list. The engine will hold when:

- `exposureBias` is not declared as supported;
- the current EV is unknown or missing from the capability list;
- the frame or scene semantic is stale;
- the model semantic is unavailable;
- the camera is not in Auto mode;
- the user has locked the parameters;
- the tradeoff is not decisive.

`SET_SHUTTER`, `SET_ISO`, and `SET_WHITE_BALANCE` are represented in the
shared model but intentionally rejected by `SafetyGuard` until the P1/P2
camera capabilities are verified.

## Debug scenarios

The tests cover the first demo scenarios:

1. Backlit person, subject-first intent -> next legal EV up.
2. Backlit person, highlight-first intent -> next legal EV down.
3. Balanced intent with weak evidence -> hold.
4. A semantic response for another frame -> hold.
5. Busy, stale, locked, duplicate-command, unknown-EV, and non-adjacent target
   cases -> reject.
