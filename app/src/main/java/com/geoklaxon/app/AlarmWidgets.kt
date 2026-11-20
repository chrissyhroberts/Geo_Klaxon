package com.geoklaxon.app

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.android.gms.location.LocationServices
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Each widget instance stores an explicit preset UUID, never an unstable list index. */
class WidgetProvider: AppWidgetProvider() {
    companion object {
        private const val TOGGLE="com.geoklaxon.WIDGET_TOGGLE"
        private val handler=Handler(Looper.getMainLooper())
        private var refreshContext:Context?=null
        private val liveRefresh=Runnable { refreshContext?.let { refreshAll(it) } }
        fun refreshAll(c: Context) {
            val mgr=AppWidgetManager.getInstance(c)
            val ids=mgr.getAppWidgetIds(ComponentName(c,WidgetProvider::class.java))
            ids.forEach { update(c,mgr,it) }
            handler.removeCallbacks(liveRefresh)
            if(AlarmStore(c).list().any { it.enabled && it.type==TriggerType.TIMER }) {
                refreshContext=c.applicationContext
                handler.postDelayed(liveRefresh,1000L)
            } else refreshContext=null
        }
        fun update(c:Context,manager:AppWidgetManager,widgetId:Int) {
            val store=AlarmStore(c)
            val a=store.getWidgetId(widgetId)?.let(store::get)
            val needsLocation=a!=null && (a.type==TriggerType.PLACE || a.type==TriggerType.BOTH)
            val hasLocation=ContextCompat.checkSelfPermission(c,android.Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(c,android.Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
            render(c,manager,widgetId,a,null,loading=needsLocation && hasLocation)
            if(needsLocation && hasLocation) {
                LocationServices.getFusedLocationProviderClient(c).lastLocation
                    .addOnSuccessListener { location ->
                        val current=store.getWidgetId(widgetId)?.let(store::get)
                        render(c,manager,widgetId,current,location,loading=false)
                    }
                    .addOnFailureListener {
                        render(c,manager,widgetId,a,null,loading=false)
                    }
            }
        }
        private fun render(c:Context,manager:AppWidgetManager,widgetId:Int,a:AlarmPreset?,location:Location?,loading:Boolean) {
            val rv=RemoteViews(c.packageName,R.layout.widget_alarm)
            rv.setTextViewText(R.id.widget_title,a?.name?.uppercase() ?: "CHOOSE AN ALARM")
            val blocked=a?.enabled==true &&
                (a.type==TriggerType.PLACE || a.type==TriggerType.BOTH) && !GeofenceRegistrar.hasPermission(c)
            rv.setTextViewText(R.id.widget_toggle, when {
                a==null->"TAP TO CONFIGURE"
                blocked->"⚠ CHECK PERMISSIONS"
                a.enabled->"● ARMED — TAP OFF"
                else->"○ OFF — TAP TO ARM"
            })
            rv.setTextColor(R.id.widget_toggle, if(a?.enabled==true)android.graphics.Color.rgb(122,255,57) else android.graphics.Color.rgb(0,240,240))
            rv.setTextViewText(R.id.widget_distance,zoneDistance(a,location,loading))
            val timer=a?.type==TriggerType.TIMER && a.enabled
            rv.setViewVisibility(R.id.widget_countdown,if(timer && a?.timerPaused!=true) android.view.View.VISIBLE else android.view.View.GONE)
            if(timer && a!=null && !a.timerPaused) {
                val remaining=timerRemaining(a)
                rv.setTextViewText(R.id.widget_countdown,formatWidgetCountdown(remaining))
                rv.setTextViewText(R.id.widget_update,"TAP TO PAUSE")
            } else rv.setTextViewText(R.id.widget_update,when {
                a?.type==TriggerType.TIMER && a.timerPaused -> "PAUSED • TAP TO RESUME"
                a?.type==TriggerType.TIME -> scheduledText(a)
                else -> locationUpdate(location)
            })
            val intent=if(a==null) Intent(c,WidgetSetupActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,widgetId)
            } else Intent(c,WidgetProvider::class.java).apply {
                action=TOGGLE;data=Uri.parse("geoklaxon://widget/$widgetId")
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,widgetId)
            }
            val pending=if(a==null) PendingIntent.getActivity(c,widgetId,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            else PendingIntent.getBroadcast(c,widgetId,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            rv.setOnClickPendingIntent(R.id.widget_root,pending)
            manager.updateAppWidget(widgetId,rv)
        }
        private fun zoneDistance(a:AlarmPreset?,location:Location?,loading:Boolean):String {
            if(a==null) return "SET UP WIDGET"
            if(a.type==TriggerType.TIME) return scheduledText(a)
            if(a.type==TriggerType.TIMER) return "TIMER"
            if(loading) return "UPDATING GPS…"
            if(location==null) return "DISTANCE UNKNOWN"
            val centre=LatLon(a.latitude,a.longitude)
            val distance=GeoMath.metresBetween(LatLon(location.latitude,location.longitude),centre)
            if(distance<=a.radiusMetres) return "● IN HOTZONE"
            val edge=(distance-a.radiusMetres).coerceAtLeast(0.0)
            return if(edge>=1000) "%.1f km TO ZONE".format(Locale.getDefault(),edge/1000.0)
            else "${edge.roundToInt()} m TO ZONE"
        }
        private fun scheduledText(a:AlarmPreset):String {
            val instant=runCatching {
                if(a.type==TriggerType.TIMER) {
                    val started=if(a.timerStartedAt>0L) a.timerStartedAt else System.currentTimeMillis()
                    Instant.ofEpochMilli(started+a.timerSeconds*1000L)
                } else ClockLogic.nextTime(a,Instant.now(),ZoneId.systemDefault())
            }.getOrNull()
            return instant?.let { SimpleDateFormat("EEE HH:mm",Locale.getDefault()).format(Date.from(it)) } ?: "TIME UNAVAILABLE"
        }
        private fun timerRemaining(a:AlarmPreset):Int {
            if(a.timerPaused) return a.timerRemainingSeconds
            val started=if(a.timerStartedAt>0L) a.timerStartedAt else System.currentTimeMillis()
            val duration=if(a.timerRemainingSeconds>0) a.timerRemainingSeconds else a.timerSeconds
            return ((started+duration*1000L-System.currentTimeMillis()+999L)/1000L).toInt().coerceAtLeast(0)
        }
        private fun formatWidgetCountdown(seconds:Int):String {
            val days=seconds/86400
            val hours=(seconds%86400)/3600
            val minutes=(seconds%3600)/60
            val secs=seconds%60
            return if(days>0) "${days}d\n${hours}h\n${minutes}m\n${secs}s"
            else "%02dh\n%02dm\n%02ds".format(Locale.getDefault(),hours,minutes,secs)
        }
        private fun locationUpdate(location:Location?):String = if(location==null) "GPS: NO FIX"
        else "GPS: ${SimpleDateFormat("HH:mm",Locale.getDefault()).format(Date(location.time))}"
    }
    override fun onUpdate(c:Context,mgr:AppWidgetManager,ids:IntArray) { ids.forEach{update(c,mgr,it)} }
    override fun onReceive(c:Context,intent:Intent) {
        super.onReceive(c,intent)
        if(intent.action!=TOGGLE) return
        val widgetId=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
        val store=AlarmStore(c)
        val presetId=store.getWidgetId(widgetId) ?: return
        val a=store.get(presetId) ?: return
        if(a.type==TriggerType.TIMER && a.enabled) {
            if(a.timerPaused) ClockScheduler.resumeTimer(c,a) else ClockScheduler.pauseTimer(c,a)
            TimedAlarmNotification.sync(c)
            WidgetProvider.refreshAll(c)
            return
        }
        if(!a.enabled)store.setInside(a.id,false)
        val rearmed=!a.enabled
        val updated=a.copy(enabled=rearmed)
        store.put(updated)
        AlarmCoordinator.changed(c,a.id)
        if(rearmed && (a.type==TriggerType.TIME || a.type==TriggerType.TIMER)) {
            ClockScheduler.rearm(c,updated)
            TimedAlarmNotification.sync(c)
        }
    }
    override fun onDeleted(c:Context,ids:IntArray) { ids.forEach{AlarmStore(c).forgetWidget(it)} }
}

class WidgetSetupActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(Activity.RESULT_CANCELED)
        setContent {
            MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFFFF39B9))) {
                Column(Modifier.fillMaxSize().background(Color(0xFF160725)).padding(16.dp)
                    .verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("⚡ WIDGET CONFIGURATOR",style=MaterialTheme.typography.headlineSmall,color=Color.Yellow)
                    Text("Pick the ONE saved alarm this widget should toggle. Create presets in GeoKlaxon first.")
                    AlarmStore(this@WidgetSetupActivity).list().forEach{preset->
                        Button(onClick={
                            if(widgetId!=AppWidgetManager.INVALID_APPWIDGET_ID) {
                                AlarmStore(this@WidgetSetupActivity).setWidgetId(widgetId,preset.id)
                                WidgetProvider.update(this@WidgetSetupActivity,AppWidgetManager.getInstance(this@WidgetSetupActivity),widgetId)
                                setResult(Activity.RESULT_OK,Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,widgetId))
                            }
                            finish()
                        },modifier=Modifier.fillMaxWidth()) {Text(preset.name)}
                    }
                }
            }
        }
    }
}
