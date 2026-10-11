# Android instrumentation tests

These tests require a physical Android device or an emulator with an accelerometer.
They validate `SensorEngine` lifecycle ownership and are intentionally separate
from the framework-independent DSP QA suite in `tools/qa/`.

Run from the project root:

```bat
gradlew.bat connectedDebugAndroidTest --stacktrace
```
