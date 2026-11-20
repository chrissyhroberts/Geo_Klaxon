# GeoKlaxon! — Android 0.2.0

**This directory contains actual source code**, created as a fresh project rather than copied from the earlier GeoKlaxon archives. It is **not an APK** and has not yet passed a full Android/Gradle build or been tested on a phone.

## Open on the Mac

1. Unzip the archive. The root folder is `GeoKlaxon`, containing `settings.gradle.kts` and `app/`.
2. **Do not overwrite existing uncommitted files** in `/Users/icrucrob/Documents/GitHub/Geo_Klaxon`.
3. Open the extracted `GeoKlaxon` folder in Android Studio (`File → Open`). Android Studio can resolve the Gradle project with Gradle 8.11.1 and JDK 17.
4. If your existing repo is empty, copy the *contents* of the extracted folder into `/Users/icrucrob/Documents/GitHub/Geo_Klaxon` (keep `.git` intact).
5. Sync Gradle, then select `app` and **Run** on an Android device with Google Play services.
6. Run `gradle :app:assembleDebug :app:testDebugUnitTest` if Gradle 8.11.1 is installed, or use Android Studio's Build and Test commands. An automated GitHub Actions build is configured but not executed.

**Gradle Wrapper limitation:** the official `gradle-wrapper.jar` binary is not bundled because it could not be fetched in this offline build environment. `gradle/wrapper/gradle-wrapper.properties` specifies Gradle 8.11.1. To generate the official wrapper using an installed Gradle 8.11.1, run `gradle wrapper --gradle-version 8.11.1` once and then use `./gradlew` normally. Do not mistake the absence of the wrapper JAR for an Android source issue.

## User-visible features

- Create/edit/delete/arm/disarm `TIME`, `PLACE` and `TIME & PLACE` alarms, with enter or leave geofence direction.
- Create one-shot `TIMER` alarms with day/hour/minute/second rocker controls. Running timers support pause, resume and `+1 MIN`.
- Weekly Monday–Sunday scheduling with a native time picker. `TIME`: scheduled clock alarm. `PLACE`: enter or leave a 50 m–10 km geofence. `TIME & PLACE`: enter or leave the fence during a scheduled time window.
- One-shot and persistent alarms; one-shot disarms when triggered or dismissed. Editing an alarm re-arms it. Exit at 125% of radius plus cooldown prevents repeated location alarms.
- Armed clock and timer alarms show persistent lock-screen notifications. Timers show live countdowns on the home screen, widget and notification.
- Four independently adjustable sound layers: beep, phone-style ring, generated brown noise and Android TTS, with three presets, volume escalation, vibration, snooze and dismissal.
- Home-screen widgets with a per-widget alarm selection, live timer countdowns and timer pause/resume interaction.
- OpenStreetMap vector basemap via MapLibre/OpenFreeMap with towns, villages, railway stations and POIs; tap to place and accurate great-circle fence geometry. **No map API key.**
- Optional UK postcode/place lookup at `api.postcodes.io`. Only lookup needs internet.
- System diagnostics for missing permissions and geofence registration results.
- Default spoken warning: `Arrgggh`; tapping the spoken-warning field selects the existing text for replacement.

## Permission setup

## APK asset

The debug APK is available at [release/GeoKlaxon-0.2.0-debug.apk](release/GeoKlaxon-0.2.0-debug.apk). SHA-256: `20b1d5ee79976e48841f6796a9b131502ec397c9c87b132746d43e1b914f69c2`.

- Android 10+: grant precise foreground location and **Allow all the time** background location through system settings (Android 11+).
- Android 13+: grant notifications. Android 12+: grant **Alarms & reminders** for precise clock delivery.
- Android 14+: full-screen alert special access may be restricted; check system settings if lock-screen UI does not appear.
- Battery restrictions, Google Play services and phone audio routing may affect reliability.

## Known risks — NOT release-ready

1. **No APK build/device testing has been performed.** The environment has a Kotlin/JVM compiler but no Android SDK, Gradle or dependency downloads. Source should be compiled and errors corrected in Android Studio before relying on it.
2. Android geofence callbacks can arrive **several minutes late**. This alpha does not offer an active foreground high-frequency journey tracking mode and **must not** be relied on to wake you before a train stop.
3. Android can suppress background service launches, full-screen intents or notification visibility. There is a notification-only fallback, but it cannot always produce the requested audible alarm.
4. Google Play services geofencing is required even though map display is entirely offline.
5. Coarse world coastlines are not street maps. UK postcode lookup returns a centroid, not the actual platform. The visible ring is a mathematically geodesic radius.
6. `TIME & PLACE` depends on geofence entry/exit events to maintain an inside state. Initial geofence registration does not trigger entry while already inside, to avoid immediately ringing at setup.
7. Only one audio session plays at once. Overlapping preset alarms replace the previous alarm; sounds inside one session are mixed.
8. The app has not been assessed against Play Store alarm permission policy, accessibility, power-management OEM differences or location provider availability.
9. Sound samples are synthesized and intentionally limited; audio-device changes need device testing.

## Design/engineering notes

- `AlarmModels.kt`: platform-free scheduling, validation, geodesy.
- `AlarmStore.kt`: persistent alarm JSON and runtime state in app-private preferences.
- `AlarmScheduling.kt`: one next occurrence per preset, alarms permission fallback and snooze recovery.
- `GeofenceRegistrar.kt`: serialized remove/readd of enter/outer-exit geofences.
- `AlarmReceivers.kt`: clock/location/reboot/event delivery.
- `AlarmPlaybackService.kt`: foreground audio mixing, TTS, vibration and token-checked snooze/dismiss controls.
- `AlarmWidgets.kt`: configured per-widget preset and live state changes.
- `OsmMap.kt`: MapLibre/OpenStreetMap basemap and destination overlay; `OfflineMap.kt` remains as the original no-network fallback and geocoder.
- `MainActivity.kt`: Compose arcade dashboard and editor.

The project has **no analytics SDK, ad network or map API keys**. The interactive basemap uses MapLibre Native and the OpenFreeMap public OpenStreetMap vector style, with visible attribution in the editor. Postcode/geocoding data attribution: [Postcodes.io](https://postcodes.io/).
