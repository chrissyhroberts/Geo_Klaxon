package com.geoklaxon.app

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId

object ClockScheduler {
    private const val CLOCK = "com.geoklaxon.CLOCK"
    private const val SNOOZE = "com.geoklaxon.SNOOZE"
    private fun manager(c: Context) = c.getSystemService(AlarmManager::class.java)
    private fun pi(c: Context, id: String, kind: String): PendingIntent = PendingIntent.getBroadcast(
        c, 0,
        Intent(c, ClockReceiver::class.java).apply {
            action = kind
            data = Uri.parse("geoklaxon://$kind/$id")
            putExtra("preset_id",id)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    fun canScheduleExact(c: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager(c).canScheduleExactAlarms()
    fun cancel(c: Context, id: String) { manager(c).cancel(pi(c,id,CLOCK)) }
    fun cancelSnooze(c: Context, id: String) {
        manager(c).cancel(pi(c,id,SNOOZE)); AlarmStore(c).setSnoozeAt(id,0)
    }
    fun schedule(c: Context, a: AlarmPreset) {
        cancel(c,a.id)
        if (!a.enabled || a.type == TriggerType.PLACE) return
        val next=if(a.type==TriggerType.TIMER) {
            if(a.timerPaused) return
            val started=if(a.timerStartedAt>0L) a.timerStartedAt else System.currentTimeMillis()
            if(a.timerStartedAt<=0L) AlarmStore(c).put(a.copy(timerStartedAt=started))
            val duration=if(a.timerRemainingSeconds>0) a.timerRemainingSeconds else a.timerSeconds
            started+duration*1000L
        } else ClockLogic.nextTime(a, Instant.now(), ZoneId.systemDefault()).toEpochMilli()
        scheduleAt(c,a,next)
    }
    /** Re-arm from a widget after a fire, never allowing the current minute to replay. */
    fun rearm(c: Context, a: AlarmPreset) {
        cancel(c,a.id)
        if (!a.enabled || a.type == TriggerType.PLACE) return
        if(a.type==TriggerType.TIMER) {
            val restarted=a.copy(timerStartedAt=System.currentTimeMillis(),timerPaused=false,timerRemainingSeconds=0)
            AlarmStore(c).put(restarted)
            schedule(c,restarted)
            return
        }
        val now=Instant.now()
        val last=AlarmStore(c).lastFired(a.id)
        val after=maxOf(now.toEpochMilli(),last+60_000L)
        val next=ClockLogic.nextTime(a,Instant.ofEpochMilli(after),ZoneId.systemDefault()).toEpochMilli()
        scheduleAt(c,a,next)
    }
    fun pauseTimer(c:Context,a:AlarmPreset) {
        if(a.type!=TriggerType.TIMER || a.timerPaused) return
        val started=if(a.timerStartedAt>0L) a.timerStartedAt else System.currentTimeMillis()
        val duration=if(a.timerRemainingSeconds>0) a.timerRemainingSeconds else a.timerSeconds
        val remaining=((started+duration*1000L-System.currentTimeMillis()+999L)/1000L).toInt().coerceAtLeast(0)
        val paused=a.copy(timerStartedAt=0L,timerPaused=true,timerRemainingSeconds=remaining)
        AlarmStore(c).put(paused)
        cancel(c,a.id)
    }
    fun resumeTimer(c:Context,a:AlarmPreset) {
        if(a.type!=TriggerType.TIMER || !a.timerPaused) return
        val resumed=a.copy(timerStartedAt=System.currentTimeMillis(),timerPaused=false)
        AlarmStore(c).put(resumed)
        schedule(c,resumed)
    }
    fun addTimerMinute(c:Context,a:AlarmPreset) {
        if(a.type!=TriggerType.TIMER || !a.enabled) return
        val now=System.currentTimeMillis()
        val started=if(a.timerStartedAt>0L) a.timerStartedAt else now
        val duration=if(a.timerPaused) a.timerRemainingSeconds
        else ((started+a.timerSeconds*1000L-now+999L)/1000L).toInt().coerceAtLeast(0)
        val extended=(duration+60).coerceAtMost(604800)
        val updated=if(a.timerPaused) a.copy(timerRemainingSeconds=extended)
        else a.copy(timerStartedAt=now,timerSeconds=extended,timerRemainingSeconds=0)
        AlarmStore(c).put(updated)
        if(!updated.timerPaused) schedule(c,updated)
    }
    private fun scheduleAt(c: Context, a: AlarmPreset, next:Long) {
        val pending = pi(c,a.id,CLOCK)
        val alarms = manager(c)
        try {
            if (canScheduleExact(c)) {
                // Deliver the broadcast at the exact wall-clock minute. AlarmClockInfo
                // is useful for the system clock UI, but some OEMs do not reliably
                // deliver its broadcast PendingIntent after an app is force-stopped.
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,pending)
            } else {
                // Approximate fallback, explicitly labelled as degraded in the UI.
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,pending)
            }
        } catch (_: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,pending)
        }
    }
    fun snooze(c: Context, a: AlarmPreset) {
        val next = System.currentTimeMillis() + a.snoozeMinutes * 60_000L
        AlarmStore(c).setSnoozeAt(a.id,next)
        scheduleSnoozeAt(c,a.id,next)
    }
    private fun scheduleSnoozeAt(c: Context, id: String, at: Long) {
        val alarm=manager(c);val pending=pi(c,id,SNOOZE)
        val time=maxOf(System.currentTimeMillis()+1500,at)
        try {
            if (canScheduleExact(c)) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,time,pending)
            else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,time,pending)
        } catch (_: SecurityException) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,time,pending)
        }
    }
    fun restore(c: Context) {
        AlarmStore(c).list().forEach { a ->
            schedule(c,a)
            val due=AlarmStore(c).snoozeAt(a.id)
            if (due>0) scheduleSnoozeAt(c,a.id,due)
        }
    }
}

/** All entrypoints call this after mutating a preset. */
object AlarmCoordinator {
    fun changed(c: Context, id: String) {
        val a=AlarmStore(c).get(id)
        if(a==null) {
            ClockScheduler.cancel(c,id);ClockScheduler.cancelSnooze(c,id)
            AlarmPlaybackService.stopPreset(c,id)
        }
        else {
            ClockScheduler.schedule(c,a)
            if(!a.enabled) {
                ClockScheduler.cancelSnooze(c,id)
                AlarmPlaybackService.stopPreset(c,id)
            }
        }
        GeofenceRegistrar.requestSync(c)
        LocationMonitorService.sync(c)
        TimedAlarmNotification.sync(c)
        WidgetProvider.refreshAll(c)
    }
    fun status(c: Context): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            add("Notifications permission missing — alarms may not be visible")
        if (!ClockScheduler.canScheduleExact(c)) add("Exact alarm access missing — clock triggers can be late")
        val places=AlarmStore(c).list().any { it.enabled && (it.type==TriggerType.PLACE || it.type==TriggerType.BOTH) }
        if (places && !GeofenceRegistrar.hasPermission(c)) add("Precise + background location required for geofences")
        if (places) add(AlarmStore(c).monitorStatus())
    }
}
