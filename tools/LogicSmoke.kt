package com.geoklaxon.app

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/** Run without Android or Gradle: kotlinc AlarmModels.kt tools/LogicSmoke.kt -include-runtime -d smoke.jar && java -jar smoke.jar */
fun main() {
    val zone=ZoneId.of("Europe/London")
    check(ClockLogic.nextTime(AlarmPreset(daysMask=1,hour=7,minute=30),
        Instant.parse("2026-11-20T10:00:00Z"),zone)==Instant.parse("2026-11-23T07:30:00Z"))
    check(ClockLogic.nextTime(AlarmPreset(daysMask=1,hour=7,minute=30),
        Instant.parse("2026-11-23T07:30:00Z"),zone)==Instant.parse("2026-11-30T07:30:00Z"))
    check(ClockLogic.nextTime(AlarmPreset(daysMask=64,hour=1,minute=30),
        Instant.parse("2026-03-28T12:00:00Z"),zone)==Instant.parse("2026-03-29T01:30:00Z"))
    val a=AlarmPreset(type=TriggerType.BOTH,daysMask=1,hour=22,minute=30,windowMinutes=180)
    check(ClockLogic.isWindowOpen(a,Instant.parse("2026-11-23T23:10:00Z"),zone))
    check(ClockLogic.isWindowOpen(a,Instant.parse("2026-11-24T00:30:00Z"),zone))
    check(!ClockLogic.isWindowOpen(a,Instant.parse("2026-11-24T02:30:00Z"),zone))
    check(!ClockLogic.isWindowOpen(a,Instant.parse("2026-11-22T23:10:00Z"),zone))
    check(AlarmPreset().valid());check(!AlarmPreset(radiusMetres=100).valid());check(!AlarmPreset(daysMask=0).valid())
    val c=LatLon(53.9583,-1.0803)
    check(GeoMath.circle(c.lat,c.lon,2000.0,72).all { abs(GeoMath.metresBetween(c,it)-2000.0)<1.0 })
    check(GeoMath.circle(0.0,179.99,5000.0).all{it.lon in -180.0..180.0})
    println("PASS: 9 scheduling, DST, window, validation and geodesic checks")
}
