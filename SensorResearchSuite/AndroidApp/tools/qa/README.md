# SensorMax Pure Kotlin QA

This is a framework-independent regression harness for the production DSP/calibration source.
It intentionally does not require the Android SDK.

## Windows / Android Studio machine

From the project root, with a JDK compatible with Kotlin 2.1.20:

```bat
kotlinc app\src\main\java\com\research\sensormax\Fft.kt app\src\main\java\com\research\sensormax\Calibration.kt app\src\main\java\com\research\sensormax\MountingCheck.kt app\src\main\java\com\research\sensormax\LiveWaveformFilter.kt app\src\main\java\com\research\sensormax\VibrationAnalyzer.kt app\src\main\java\com\research\sensormax\MeasurementQuality.kt tools\qa\SensorMaxPureQa.kt -Werror -include-runtime -d SensorMaxPureQa.jar
java -jar SensorMaxPureQa.jar
```

Expected final line:

```text
PURE_KOTLIN_QA_PASS
```

## Covered checks

- FFT frequency/amplitude behavior
- FFT inverse reconstruction
- static calibration statistics
- mounting stability
- live waveform filtering
- nanosecond timestamp-derived sample rate/jitter
- analytical acceleration → velocity/displacement conversion
- 1X/2X/3X running-speed harmonic detection
- controlled envelope modulation


## Network inbound acquisition QA

`NetworkAcquisitionQa.kt` covers the SensorMax-native TCP/NDJSON provider contract and loopback path. It verifies versioned parsing, normalized source identity, local monotonic timestamp assignment, provider start/stop, unsupported-version rejection, non-finite-value rejection, empty-array rejection, and bounded default configuration.


## Localization resource integrity

The Android resource gate requires every English `string` key to have an equivalent key in `values-id/strings.xml` and `values-zh-rCN/strings.xml`, checks positional format placeholders, unresolved resource references, duplicate keys, and common UTF-8 mojibake markers. The dedicated regression suite also checks XML parsing, exact locale key-set parity, representative translations, preserved engineering units/channel labels, and UTF-8 round-trip integrity.

Run from the repository root:

```bash
python AndroidApp/tools/qa/resource_integrity_check.py
python -m unittest discover -s AndroidApp/tools/qa -p 'test_localization_resources.py' -v
python AndroidApp/tools/qa/source_integrity_check.py
python -m unittest discover -s AndroidApp/tools/qa -p 'test_diagnostic_visuals.py' -v
```

The diagnostic-visual regression suite checks the semantic RGB tokens for the X/Y/Z waveform, AUTO triaxial visibility, severity/category spans, log-line bounds, and diagnostic TextView readability. These are source-level checks; they do not replace rendering tests on supported Android devices or emulator configurations.
