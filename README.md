# DAGR

DAGR is an Android hiking and navigation app inspired by the glanceable layout of dedicated GPS receivers, with touch-friendly controls. The initial release focuses on a live compass, current position, waypoint collections, a map overview, and GPX waypoint exchange.

## Project status

The Android project is under `app/`. Current first-release scope and future milestones are tracked in [ROADMAP.md](ROADMAP.md).

## Build and run

Requirements:

- Android Studio with JDK 17
- Android SDK Platform 35 (or a newer compatible platform)
- Android Gradle Plugin 8.7.3 / Gradle 8.9
- An Android device or emulator running Android 8.0 (API 26) or later

Open the repository in Android Studio and sync the Gradle project, or build from PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

The first launch requests location permission. The compass uses the device rotation-vector sensor. The map uses OpenStreetMap raster tiles and displays attribution; map tiles require an internet connection. Waypoints and GPX import/export are stored locally and remain available offline.

## Data formats and coordinate display

The app stores waypoint coordinates as latitude/longitude and supports GPX waypoint import/export. Coordinates can be displayed as decimal latitude/longitude or MGRS. GPX is the initial exchange format; see the roadmap for planned extensions.

## Navigation safety

This app is a recreational navigation aid, not a certified safety instrument. Phone GPS and compass readings can be inaccurate or unavailable. Check fix accuracy, carry suitable backup navigation equipment, and follow local safety guidance.
