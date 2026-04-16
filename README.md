# Android IMU PDR Navigation

An Android pedestrian dead reckoning (PDR) demo app built with Kotlin and Jetpack Compose.

This project focuses on real-time pedestrian positioning with on-device 9-axis IMU fusion:

- accelerometer, gyroscope, magnetometer, and rotation vector integration
- adaptive step detection and cadence estimation
- dynamic step-length estimation
- heading estimation with light GPS-assisted correction
- real-time trajectory rendering on map tiles
- optional hardware pedometer display
- local IMU/PDR CSV logging for offline analysis

## Current App Features

- Real-time PDR tracking UI
- Chinese interface with collapsible top and bottom panels
- HD map and satellite map switching
- Heading marker on the map and direction badge in the UI
- Local data cleanup button for logs and cached map tiles
- Android Studio JDK 21 compatible Gradle setup

## Project Structure

- `app/src/main/java/com/example/myapplication/MainActivity.kt`
  Main Compose UI, sensor wiring, location updates, map rendering, and controls
- `app/src/main/java/com/example/myapplication/NineAxisPdrEngine.kt`
  Core PDR engine for step detection, heading fusion, and local trajectory estimation
- `app/src/main/java/com/example/myapplication/ImuCsvLogger.kt`
  CSV session logger for IMU and PDR outputs
- `app/src/main/java/com/example/myapplication/CoordinateTransformUtil.kt`
  Coordinate conversion utilities
- `docs/pdr_system_report.tex`
  Detailed Chinese LaTeX report covering architecture, algorithm design, experiments, and evaluation
- `tools/generate_pdr_ppt_from_template.py`
  Helper script for generating the presentation from the course PPT template

## Requirements

- Android Studio with JDK 21 available
- Android SDK installed
- Android device with motion sensors
- A device with a full 9-axis IMU is recommended

## Build

Use Android Studio or run:

```bash
gradlew.bat :app:assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Run

1. Open the project in Android Studio.
2. Confirm Gradle uses a JDK 21 runtime.
3. Start an emulator or connect a real Android phone.
4. Grant location permission.
5. Tap the start button and wait for the GPS start fix.

## Notes

- The current implementation is intended as a practical PDR prototype rather than a production-grade navigation stack.
- Long-session drift still depends on device quality, carrying pose, and magnetometer conditions.
- GPS is used only as a light assist to reduce accumulated heading and trajectory drift.

## Report

The repository includes a detailed Chinese LaTeX report:

```text
docs/pdr_system_report.tex
```

Recommended build command:

```bash
xelatex docs/pdr_system_report.tex
```

The report includes:

- development process and implementation details
- 6-axis and 9-axis AHRS principles
- filtering and peak detection strategy
- step-length estimation and interruption correction
- experiment template pages for later completion
- evaluation metrics, strengths, limitations, and future work

## Future Improvements

- better step-length personalization
- pose classification for hand/waist/pocket usage
- floor change detection
- map matching
- calibration workflow and benchmark dataset tooling
