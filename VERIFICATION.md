# Rebuild verification record

This project was recreated from scratch on 20 November 2026. Checks are documented here when actually run. **A full Android build requires Android SDK + Gradle + dependency downloads and cannot be claimed from source inspection alone.**

- Rebuilt source tree independently of previous archives.
- Kotlin/JVM smoke tests: see test result below after running `tools/LogicSmoke.kt`.
- Android XML parse: see output below.
- Offline world map: validate `world_countries.geojson` and EPSG:4326 coordinates.
- ZIP archive: validate CRC and required project files.
- APK assemble + physical device tests: **NOT RUN**.
