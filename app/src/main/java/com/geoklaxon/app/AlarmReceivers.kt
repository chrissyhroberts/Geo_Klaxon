package com.geoklaxon.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import java.time.Instant
import java.time.ZoneId

class ClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id=intent.data?.lastPathSegment ?: intent.getStringExtra("preset_id") ?: return
        val store=AlarmStore(context)
        val preset=store.get(id) ?: return
        if(intent.action==TimedAlarmNotification.TOGGLE) {
            store.put(preset.copy(enabled=!preset.enabled))
            AlarmCoordinator.changed(context,id)
            return
        }
        if(intent.action==TimedAlarmNotification.TOGGLE_PAUSE) {
            if(preset.type==TriggerType.TIMER && preset.enabled) {
                if(preset.timerPaused) ClockScheduler.resumeTimer(context,preset)
                else ClockScheduler.pauseTimer(context,preset)
                TimedAlarmNotification.sync(context)
                WidgetProvider.refreshAll(context)
            }
            return
        }
        if(intent.action==TimedAlarmNotification.ADD_MINUTE) {
            if(preset.type==TriggerType.TIMER && preset.enabled) {
                ClockScheduler.addTimerMinute(context,preset)
                TimedAlarmNotification.sync(context)
                WidgetProvider.refreshAll(context)
            }
            return
        }
        when(intent.action) {
            "com.geoklaxon.SNOOZE" -> {
                if(store.snoozeAt(id)<=0) return
                store.setSnoozeAt(id,0)
                AlarmDispatch.fire(context,preset,fromSnooze=true)
            }
            "com.geoklaxon.CLOCK" -> {
                if(!preset.enabled || preset.type==TriggerType.PLACE) return
                if(System.currentTimeMillis()-store.lastFired(id)<10_000L) return
                if(preset.type==TriggerType.TIME || preset.type==TriggerType.TIMER) {
                    AlarmDispatch.fire(context,preset)
                } else if(preset.direction==GeofenceDirection.ENTER && store.isInside(id) &&
                    ClockLogic.isWindowOpen(preset,Instant.now(),ZoneId.systemDefault())) {
                    AlarmDispatch.fire(context,preset)
                }
                // A one-shot COMBINED alarm stays armed until BOTH conditions are met.
                val updated=store.get(id)
                if(updated!=null) ClockScheduler.schedule(context,updated)
                TimedAlarmNotification.sync(context)
            }
        }
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event=GeofencingEvent.fromIntent(intent) ?: return
        val store=AlarmStore(context)
        if(event.hasError()) {
            store.setMonitorStatus("Geofence error ${event.errorCode}; open GeoKlaxon to retry")
            return
        }
        val now=System.currentTimeMillis()
        for (fence in event.triggeringGeofences.orEmpty()) {
            val raw=fence.requestId
            val id=raw.substringAfter(':',"")
            val preset=store.get(id) ?: continue
            if (!preset.enabled || preset.type==TriggerType.TIME || preset.type==TriggerType.TIMER) continue
            if(raw.startsWith("out:") && event.geofenceTransition==Geofence.GEOFENCE_TRANSITION_EXIT) {
                store.setInside(id,false)
            }
            if(raw.startsWith("in:") && event.geofenceTransition==Geofence.GEOFENCE_TRANSITION_ENTER) {
                val wasInside=store.isInside(id)
                store.setInside(id,true)
                if(preset.direction==GeofenceDirection.ENTER) {
                    if(wasInside) continue
                    val eligible=preset.type==TriggerType.PLACE ||
                        ClockLogic.isWindowOpen(preset,Instant.now(),ZoneId.systemDefault())
                    if (eligible && (now-store.lastFired(id)>=preset.cooldownMinutes*60_000L)) {
                        AlarmDispatch.fire(context,preset)
                    }
                }
            }
            if(raw.startsWith("leave:") && event.geofenceTransition==Geofence.GEOFENCE_TRANSITION_EXIT) {
                val wasInside=store.isInside(id)
                store.setInside(id,false)
                if(preset.direction==GeofenceDirection.EXIT && wasInside) {
                    val eligible=preset.type==TriggerType.PLACE ||
                        ClockLogic.isWindowOpen(preset,Instant.now(),ZoneId.systemDefault())
                    if (eligible && (now-store.lastFired(id)>=preset.cooldownMinutes*60_000L)) {
                        AlarmDispatch.fire(context,preset)
                    }
                }
            }
        }
        WidgetProvider.refreshAll(context)
    }
}

class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if(intent.action==android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED && !ClockScheduler.canScheduleExact(context)) return
        if(intent.action==Intent.ACTION_BOOT_COMPLETED || intent.action==Intent.ACTION_MY_PACKAGE_REPLACED) {
            AlarmStore(context).list().filter { it.type==TriggerType.PLACE || it.type==TriggerType.BOTH }.forEach {
                AlarmStore(context).setInside(it.id,false)
            }
        }
        ClockScheduler.restore(context)
        GeofenceRegistrar.requestSync(context)
        LocationMonitorService.sync(context)
        TimedAlarmNotification.sync(context)
        WidgetProvider.refreshAll(context)
    }
}

object AlarmDispatch {
    fun fire(c: Context, preset: AlarmPreset, fromSnooze: Boolean=false) {
        val store=AlarmStore(c)
        if(!fromSnooze) {
            store.setLastFired(preset.id,System.currentTimeMillis())
            if(preset.mode==AlarmMode.ONE_SHOT || preset.type==TriggerType.TIMER) {
                store.put(preset.copy(enabled=false))
                // Disarm future scheduling without sending STOP_PRESET: this alarm
                // is about to start playback, and the stop command can race it.
                ClockScheduler.cancel(c,preset.id)
                ClockScheduler.cancelSnooze(c,preset.id)
                GeofenceRegistrar.requestSync(c)
                LocationMonitorService.sync(c)
                TimedAlarmNotification.sync(c)
                WidgetProvider.refreshAll(c)
            }
        }
        AlarmPlaybackService.start(c,preset)
    }
}
