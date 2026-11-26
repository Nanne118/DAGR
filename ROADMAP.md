# Product roadmap

This roadmap records product ideas beyond the sensible first release so that later additions can be prioritized without expanding the initial scope prematurely. It is intentionally a set of candidate milestones rather than a promise or fixed schedule.

## First release

- Live position and device heading on a glanceable Navigate screen
- Selected waypoint bearing and distance
- Coordinate display setting: decimal latitude/longitude or MGRS
- Interactive map with current-position and waypoint markers
- Multiple locally stored waypoint collections
- Add, edit, select, and delete waypoints
- GPX waypoint import and export
- GPS permission/fix status and graceful handling when sensors are unavailable

## Candidate follow-up milestones

### Navigation quality

- Track recording with pause/resume, elapsed time, distance, and a GPX track export
- Routes, route legs, and turn-by-turn bearing/distance to the next route point
- Elevation, speed, trip statistics, and optional arrival-radius alerts
- Magnetic/true-north preference, declination handling, and configurable angular units
- Better compass calibration guidance and sensor quality reporting
- Lock-screen/foreground navigation mode with carefully scoped background location use

### Maps and offline use

- Downloadable offline map regions with storage estimates, cancellation, and cleanup
- Explicit basemap/source selection with attribution and provider-specific usage terms
- Topographic and satellite layers where an appropriate licensed provider is available
- Map search, scale bar, north-up/heading-up modes, and richer map annotations
- Map data and tile-cache management, including storage limits and diagnostics

### Data and interoperability

- CSV import/export with a user-visible column mapping and coordinate-format selection
- KML/KMZ and GeoJSON support after their import/export semantics are defined
- GPX route and track import/export, beyond the initial waypoint-only support
- Import preview, duplicate detection/merge options, validation report, and undo
- Collection metadata, colors/icons, sorting, sharing, and backup/restore
- Datum and coordinate-system selection only with well-tested conversion and clear labeling

### Usability and accessibility

- High-contrast and night themes, optional red-light-friendly palette, and adjustable text size
- Haptic/audio arrival cues and configurable proximity thresholds
- Larger controls, screen-reader descriptions, and improved one-handed layouts
- Search/filter, bulk selection, and collection organization tools
- Optional Pebble Time 2 companion display, subject to device and platform support

## Design and engineering guardrails

- Keep the app offline-first for user data; network access is for map tiles unless a future feature explicitly needs more.
- Keep imported/exported formats behind small format-specific codecs, with a normalized internal coordinate model.
- Validate file content and coordinates before writing data; report skipped or invalid records rather than silently dropping them.
- Do not add background location, cloud sync, analytics, or account requirements without a clear user benefit and consent model.
- Treat map imagery and tile caches according to the provider's licensing, attribution, and usage rules.
- Keep navigation indicators honest about stale fixes, GPS accuracy, and unavailable sensors.
- Add focused tests for coordinate conversion, GPX parsing/export, and any route or track calculations as those features are introduced.

## Deferred decisions

- Which offline map provider and tile licensing model to use
- Whether route guidance belongs in the core app or a later navigation mode
- Which additional exchange formats are most valuable to users
- Whether cross-platform support justifies a second UI implementation
- Whether watch support should be a companion app, notification surface, or a later integration
