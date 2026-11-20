package com.geoklaxon.app

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ClockLogicTest {
    private val london=ZoneId.of("Europe/London")
    @Test fun mondayAlarmSchedulesMonday() {
        val a=AlarmPreset(daysMask=1,hour=7,minute=30)
        assertEquals(Instant.parse("2026-11-23T07:30:00Z"),ClockLogic.nextTime(a,Instant.parse("2026-11-20T10:00:00Z"),london))
    }
    @Test fun passedClockSchedulesNextWeek() {
        val a=AlarmPreset(daysMask=1,hour=7,minute=30)
        assertEquals(Instant.parse("2026-11-30T07:30:00Z"),ClockLogic.nextTime(a,Instant.parse("2026-11-23T07:30:00Z"),london))
    }
    @Test fun DSTGapRollsForward() {
        val a=AlarmPreset(daysMask=64,hour=1,minute=30)
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"),ClockLogic.nextTime(a,Instant.parse("2026-03-28T12:00:00Z"),london))
    }
    @Test fun combinedWindowRequiresMatchingDay() {
        val a=AlarmPreset(type=TriggerType.BOTH,daysMask=1,hour=22,minute=30,windowMinutes=180)
        assertTrue(ClockLogic.isWindowOpen(a,Instant.parse("2026-11-23T23:10:00Z"),london))
        assertTrue(ClockLogic.isWindowOpen(a,Instant.parse("2026-11-24T00:30:00Z"),london))
        assertFalse(ClockLogic.isWindowOpen(a,Instant.parse("2026-11-24T02:30:00Z"),london))
        assertFalse(ClockLogic.isWindowOpen(a,Instant.parse("2026-11-22T23:10:00Z"),london))
    }
    @Test fun defaultPresetIsValid() { assertTrue(AlarmPreset().valid()) }
    @Test fun rejectsInvalidFence() { assertFalse(AlarmPreset(radiusMetres=25).valid()) }
    @Test fun rejectsNoWeekdays() { assertFalse(AlarmPreset(daysMask=0).valid()) }
}
