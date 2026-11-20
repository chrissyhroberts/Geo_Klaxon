# GeoKlaxon 0.2.1

## Fixes

- Fixed timer countdown consistency across the home screen, widget and lock notification.
- Replaced the widget chronometer with a live refreshed countdown, including vertical long-duration formatting.
- Removed the widget mascot icon to reclaim 1×1 widget space.
- Fixed `+1 MIN` to extend from the timer’s actual remaining duration.
- Corrected timer rocker snapping and zero-value handling.

## Notes

- This source release has not yet had a fresh APK built in the constrained environment.

# GeoKlaxon 0.2.0

## Highlights

- Added persistent lock-screen status for armed clock and timer alarms.
- Added live timer countdowns on the home screen, widget, and lock notification.
- Added timer pause/resume and `+1 MIN` controls.
- Added swipeable, snapping day/hour/minute/second timer rockers.
- Added staged alarm audio with scream and abrasive inverse-sine layers.
- Added automatic disarm on dismissal and automatic re-arm when editing or using the widget.
- Added OpenStreetMap/MapLibre basemap with towns, villages, railway stations and POIs, without a map API key.
- Improved one-shot timing, notification wording, widget layout, and notification iconography.

## Notes

- Timer alarms are one-shot only and require a non-zero duration.
- `TIME & PLACE` remains the user-facing name for the combined trigger type.
- Debug APK: `release/GeoKlaxon-0.2.0-debug.apk`.
- APK SHA-256: `20b1d5ee79976e48841f6796a9b131502ec397c9c87b132746d43e1b914f69c2`.
- Android Studio/device verification is still required before treating this as production-ready.
