package com.geoklaxon.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Keeps armed location alarms visible and live while the screen is locked. */
class LocationMonitorService : Service() {
    companion object {
        private const val CHANNEL="geo_klaxon_location"
        private const val NOTIFICATION=8031
        private const val START="com.geoklaxon.START_LOCATION_MONITOR"
        private const val STOP="com.geoklaxon.STOP_LOCATION_MONITOR"
        private const val TOGGLE="com.geoklaxon.TOGGLE_LOCATION_ALARM"
        private const val EXTRA_ID="preset_id"

        fun sync(context:Context) {
            val c=context.applicationContext
            val presets=AlarmStore(c).list()
            val hasLocationAlarms=presets.any { it.enabled && (it.type==TriggerType.PLACE || it.type==TriggerType.BOTH) }
            val hasPermission=ContextCompat.checkSelfPermission(c,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(c,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
            val backgroundReady=GeofenceRegistrar.hasPermission(c)
            if(hasLocationAlarms && hasPermission && backgroundReady) {
                runCatching {
                    ContextCompat.startForegroundService(c,Intent(c,LocationMonitorService::class.java).apply { action=START })
                }.onFailure { AlarmStore(c).setMonitorStatus("Live location service blocked: ${it.javaClass.simpleName}") }
            } else {
                c.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
                c.stopService(Intent(c,LocationMonitorService::class.java).apply { action=STOP })
            }
        }
    }

    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var callback:LocationCallback?=null
    private var lastLocation:Location?=null
    private var notificationTargetId:String?=null

    override fun onBind(intent:Intent?):IBinder?=null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,"GeoKlaxon live location",NotificationManager.IMPORTANCE_LOW).apply {
                description="Live distance updates for armed location alarms"
                lockscreenVisibility=Notification.VISIBILITY_PUBLIC
            })
    }

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==STOP) {
            stopMonitoring()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if(intent?.action==TOGGLE) {
            val id=intent.getStringExtra(EXTRA_ID)
            val store=AlarmStore(this)
            store.get(id ?: "")?.let { preset ->
                store.put(preset.copy(enabled=!preset.enabled))
                notificationTargetId=preset.id
                AlarmCoordinator.changed(this,preset.id)
            }
        }
        val configured=AlarmStore(this).list().filter { it.enabled && (it.type==TriggerType.PLACE || it.type==TriggerType.BOTH) }
        if(configured.isEmpty()) {
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
            stopSelf();return START_NOT_STICKY
        }
        startForeground(NOTIFICATION,notification(lastLocation))
        if(configured.any { it.enabled && it.type!=TriggerType.TIME }) startMonitoring() else stopMonitoring()
        return START_STICKY
    }

    private fun startMonitoring() {
        if(callback!=null) return
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED) return
        val request=LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY,60_000L)
            .setMinUpdateIntervalMillis(15_000L)
            .setWaitForAccurateLocation(false)
            .build()
        val newCallback=object:LocationCallback() {
            override fun onLocationResult(result:LocationResult) {
                lastLocation=result.lastLocation
                WidgetProvider.refreshAll(this@LocationMonitorService)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification(lastLocation))
            }
        }
        callback=newCallback
        runCatching { client.requestLocationUpdates(request,newCallback,mainLooper) }
            .onFailure {
                callback=null
                AlarmStore(this).setMonitorStatus("Live location failed: ${it.javaClass.simpleName}")
            }
    }

    private fun stopMonitoring() {
        callback?.let { client.removeLocationUpdates(it) }
        callback=null
    }

    private fun notification(location:Location?):Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val configured=AlarmStore(this).list().filter { it.enabled && (it.type==TriggerType.PLACE || it.type==TriggerType.BOTH) }
        val activeLocations=configured
        val activeTimed=emptyList<AlarmPreset>()
        val target=notificationTargetId?.let { id -> configured.firstOrNull { it.id==id } }
            ?: activeLocations.firstOrNull()
            ?: activeTimed.firstOrNull()
            ?: configured.firstOrNull()
        val nearest=if(location==null) null else activeLocations.minByOrNull {
            GeoMath.metresBetween(LatLon(location.latitude,location.longitude),LatLon(it.latitude,it.longitude))
        }
        val locationStatus=when {
            activeLocations.isEmpty()->null
            location==null->"Waiting for GPS fix"
            nearest==null->"Location monitoring active"
            else->{
                val metres=GeoMath.metresBetween(LatLon(location.latitude,location.longitude),LatLon(nearest.latitude,nearest.longitude))
                if(metres<=nearest.radiusMetres) "Inside ${nearest.name} hotzone"
                else "${nearest.name}: ${formatDistance((metres-nearest.radiusMetres).coerceAtLeast(0.0))} to zone"
            }
        }
        val timedStatus=activeTimed.firstOrNull()?.let { preset ->
            runCatching {
                val next=ClockLogic.nextTime(preset,Instant.now(),ZoneId.systemDefault())
                "Next ${preset.name}: ${SimpleDateFormat("EEE HH:mm",Locale.getDefault()).format(Date.from(next))}"
            }.getOrDefault("Timed alarm armed")
        }
        val distance=locationStatus ?: timedStatus ?: "No armed alarms"
        val updated=location?.let { "GPS updated ${SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date(it.time))}" } ?: "GPS update pending"
        val detail=listOfNotNull(distance,if(locationStatus!=null) timedStatus else null,if(locationStatus!=null) updated else null).joinToString("\n")
        val toggle=PendingIntent.getService(this, target?.id?.hashCode() ?: 0,
            Intent(this,LocationMonitorService::class.java).apply {
                action=TOGGLE
                putExtra(EXTRA_ID,target?.id)
                data=Uri.parse("geoklaxon://toggle/${target?.id ?: "none"}")
            },PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(target?.name ?: "GeoKlaxon")
            .setContentText(distance)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .addAction(0,if(target?.enabled==true) "DISARM" else "ARM",toggle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun formatDistance(metres:Double):String = if(metres>=1000) "%.1f km".format(Locale.getDefault(),metres/1000.0)
    else "${metres.roundToInt()} m"

    override fun onDestroy() {
        stopMonitoring()
        super.onDestroy()
    }
}
