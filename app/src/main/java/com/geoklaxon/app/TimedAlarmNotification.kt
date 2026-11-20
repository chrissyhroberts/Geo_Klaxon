package com.geoklaxon.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

/** Persistent status notification for clock alarms; it does not require GPS or a foreground service. */
object TimedAlarmNotification {
    private const val CHANNEL="geo_klaxon_timed"
    private const val NOTIFICATION=8032
    const val TOGGLE="com.geoklaxon.TOGGLE_TIMED_ALARM"
    const val TOGGLE_PAUSE="com.geoklaxon.TOGGLE_TIMER_PAUSE"
    const val ADD_MINUTE="com.geoklaxon.ADD_TIMER_MINUTE"
    const val EXTRA_ID="preset_id"

    fun sync(context:Context) {
        val c=context.applicationContext
        val manager=c.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"GeoKlaxon timed alarms",NotificationManager.IMPORTANCE_LOW).apply {
            description="Persistent status for armed clock alarms"
            lockscreenVisibility=Notification.VISIBILITY_PUBLIC
        })
        val alarm=AlarmStore(c).list().firstOrNull { it.enabled && (it.type==TriggerType.TIME || it.type==TriggerType.TIMER) }
        if(alarm==null) {
            manager.cancel(NOTIFICATION)
            return
        }
        val next=runCatching {
            if(alarm.type==TriggerType.TIMER) {
                val started=if(alarm.timerStartedAt>0L) alarm.timerStartedAt else System.currentTimeMillis()
                Instant.ofEpochMilli(started+alarm.timerSeconds*1000L)
            }
            else ClockLogic.nextTime(alarm,Instant.now(),ZoneId.systemDefault())
        }.getOrNull()
        val timerRemaining=if(alarm.type==TriggerType.TIMER) {
            if(alarm.timerPaused) alarm.timerRemainingSeconds
            else ((next?.toEpochMilli()?.minus(System.currentTimeMillis()) ?: 0L)/1000L).toInt().coerceAtLeast(0)
        } else 0
        val whenText=if(alarm.type==TriggerType.TIMER) {
            if(alarm.timerPaused) "PAUSED • ${formatDuration(timerRemaining)}"
            else formatDuration(timerRemaining)
        } else next?.let {
            val zone=ZoneId.systemDefault()
            val localNext=it.atZone(zone)
            val localNow=Instant.now().atZone(zone)
            val day=if(localNext.toLocalDate()==localNow.toLocalDate()) "Today"
            else localNext.dayOfWeek.getDisplayName(TextStyle.FULL,Locale.getDefault())
            "$day at ${SimpleDateFormat("HH:mm",Locale.getDefault()).format(Date.from(it))}"
        } ?: "time scheduled"
        val open=PendingIntent.getActivity(c,0,Intent(c,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val toggle=PendingIntent.getBroadcast(c,alarm.id.hashCode(),Intent(c,ClockReceiver::class.java).apply {
            action=TOGGLE
            data=Uri.parse("geoklaxon://timed-toggle/${alarm.id}")
            putExtra(EXTRA_ID,alarm.id)
        },PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause=PendingIntent.getBroadcast(c,alarm.id.hashCode()+1,Intent(c,ClockReceiver::class.java).apply {
            action=TOGGLE_PAUSE
            data=Uri.parse("geoklaxon://timer-pause/${alarm.id}")
            putExtra(EXTRA_ID,alarm.id)
        },PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val addMinute=PendingIntent.getBroadcast(c,alarm.id.hashCode()+2,Intent(c,ClockReceiver::class.java).apply {
            action=ADD_MINUTE
            data=Uri.parse("geoklaxon://timer-add-minute/${alarm.id}")
            putExtra(EXTRA_ID,alarm.id)
        },PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder=NotificationCompat.Builder(c,CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(alarm.name)
            .setContentText(whenText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(whenText))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if(alarm.type==TriggerType.TIMER && !alarm.timerPaused && next!=null)
            builder.setWhen(next.toEpochMilli()).setUsesChronometer(true).setChronometerCountDown(true)
        if(alarm.type==TriggerType.TIMER) builder.addAction(0,if(alarm.timerPaused) "RESUME" else "PAUSE",pause)
        if(alarm.type==TriggerType.TIMER) builder.addAction(0,"+1 MIN",addMinute)
        builder.addAction(0,"DISARM",toggle)
        manager.notify(NOTIFICATION,builder.build())
    }

    private fun formatDuration(seconds:Int):String {
        val days=seconds/86400
        val hours=(seconds%86400)/3600
        val minutes=(seconds%3600)/60
        val secs=seconds%60
        return if(days>0) "%dd %02d:%02d:%02d".format(Locale.getDefault(),days,hours,minutes,secs)
        else "%02d:%02d:%02d".format(Locale.getDefault(),hours,minutes,secs)
    }
}
