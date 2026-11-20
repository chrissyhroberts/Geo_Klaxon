package com.geoklaxon.app

import java.time.*
import java.io.Serializable
import java.util.UUID
import kotlin.math.*

enum class TriggerType { TIME, TIMER, PLACE, BOTH }
enum class AlarmMode { ONE_SHOT, REPEAT }
enum class AlarmIntensity { SUBTLE, SERIOUS, SKUNK }
enum class GeofenceDirection { ENTER, EXIT }

/** Week bit 0 is Monday, bit 6 is Sunday. */
data class AlarmPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "NEW ALARM",
    val type: TriggerType = TriggerType.TIME,
    val mode: AlarmMode = AlarmMode.REPEAT,
    val enabled: Boolean = true,
    val daysMask: Int = 127,
    val hour: Int = 7,
    val minute: Int = 30,
    val timerSeconds: Int = 0,
    val timerStartedAt: Long = 0L,
    val timerPaused: Boolean = false,
    val timerRemainingSeconds: Int = 0,
    val latitude: Double = 52.0,
    val longitude: Double = -1.5,
    val radiusMetres: Int = 2000,
    val direction: GeofenceDirection = GeofenceDirection.ENTER,
    /** For BOTH, the clock defines a daily window START, not an exact ring time. */
    val windowMinutes: Int = 120,
    val intensity: AlarmIntensity = AlarmIntensity.SERIOUS,
    val beepLevel: Int = 65,
    val ringLevel: Int = 75,
    val noiseLevel: Int = 0,
    val voiceLevel: Int = 85,
    val screamLevel: Int = 0,
    val inverseSineLevel: Int = 0,
    val voiceMessage: String = "Arrgggh",
    val snoozeMinutes: Int = 5,
    val cooldownMinutes: Int = 15
) : Serializable {
    fun valid(): Boolean = name.isNotBlank() && name.length <= 60 &&
        daysMask in 1..127 && hour in 0..23 && minute in 0..59 &&
        latitude.isFinite() && latitude in -85.0..85.0 &&
        longitude.isFinite() && longitude in -180.0..180.0 &&
        radiusMetres in 50..10000 && timerSeconds in 0..604800 &&
        (type!=TriggerType.TIMER || timerSeconds>0) && timerRemainingSeconds in 0..604800 && windowMinutes in 5..360 &&
        listOf(beepLevel, ringLevel, noiseLevel, voiceLevel, screamLevel, inverseSineLevel).all { it in 0..100 } &&
        snoozeMinutes in 1..30 && cooldownMinutes in 1..180 &&
        (type!=TriggerType.TIMER || mode==AlarmMode.ONE_SHOT)
}

/** Testable clock logic. Works across weekday boundaries and DST jumps. */
object ClockLogic {
    fun nextTime(preset: AlarmPreset, after: Instant, zone: ZoneId): Instant {
        val local = after.atZone(zone)
        for (offset in 0..8) {
            val date = local.toLocalDate().plusDays(offset.toLong())
            val bit = 1 shl (date.dayOfWeek.value - 1)
            if (preset.daysMask and bit == 0) continue
            val candidate = LocalDateTime.of(date, LocalTime.of(preset.hour, preset.minute))
                .atZone(zone).toInstant()
            if (candidate.isAfter(after)) return candidate
        }
        error("At least one weekday must be enabled")
    }

    /** Combined alarms are eligible during the specified local-time window, including overnight. */
    fun isWindowOpen(preset: AlarmPreset, now: Instant, zone: ZoneId): Boolean {
        val z = now.atZone(zone)
        for (dayOffset in 0..1) {
            val date = z.toLocalDate().minusDays(dayOffset.toLong())
            val bit = 1 shl (date.dayOfWeek.value - 1)
            if (preset.daysMask and bit == 0) continue
            val start = LocalDateTime.of(date, LocalTime.of(preset.hour, preset.minute))
                .atZone(zone).toInstant()
            if (!now.isBefore(start) && now.isBefore(start.plusSeconds(preset.windowMinutes * 60L))) return true
        }
        return false
    }
}

data class LatLon(val lat: Double, val lon: Double)
object GeoMath {
    private const val EARTH_METRES = 6371008.8
    fun circle(lat: Double, lon: Double, radiusMetres: Double, count: Int = 72): List<LatLon> {
        val phi1 = Math.toRadians(lat)
        val lam1 = Math.toRadians(lon)
        val delta = radiusMetres / EARTH_METRES
        return (0 until count).map { k ->
            val bearing = 2.0 * Math.PI * k / count
            val phi2 = asin(sin(phi1) * cos(delta) + cos(phi1) * sin(delta) * cos(bearing))
            val lam2 = lam1 + atan2(sin(bearing) * sin(delta) * cos(phi1), cos(delta) - sin(phi1) * sin(phi2))
            LatLon(Math.toDegrees(phi2), ((Math.toDegrees(lam2) + 540) % 360) - 180)
        }
    }
    fun metresBetween(a: LatLon, b: LatLon): Double {
        val lat1 = Math.toRadians(a.lat); val lat2 = Math.toRadians(b.lat)
        val dLat = lat2 - lat1; val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * EARTH_METRES * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
