package com.geoklaxon.app

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/** Serialises remove/readd operations; a sync requested during an in-flight one is replayed. */
object GeofenceRegistrar {
    private val guard = Any()
    private var running=false
    private var dirty=false
    fun hasPermission(c: Context): Boolean =
        ContextCompat.checkSelfPermission(c,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED &&
        (Build.VERSION.SDK_INT<29 || ContextCompat.checkSelfPermission(c,Manifest.permission.ACCESS_BACKGROUND_LOCATION)==PackageManager.PERMISSION_GRANTED)

    private fun pending(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c,0,Intent(c,GeofenceReceiver::class.java).apply { action="com.geoklaxon.GEOFENCE" },
        PendingIntent.FLAG_UPDATE_CURRENT or (if(Build.VERSION.SDK_INT>=31) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
    )
    fun requestSync(context: Context) {
        val c=context.applicationContext
        val shouldRun=synchronized(guard) {
            dirty=true
            if(running) false else { running=true;true }
        }
        if(shouldRun) reconcile(c)
    }
    private fun complete(c: Context) {
        val rerun=synchronized(guard) {
            running=false
            if(dirty){ running=true;true } else false
        }
        if(rerun) reconcile(c)
    }
    private fun reconcile(c: Context) {
        synchronized(guard){dirty=false}
        val store=AlarmStore(c)
        val client=LocationServices.getGeofencingClient(c)
        val active=store.list().filter { it.enabled && (it.type==TriggerType.PLACE || it.type==TriggerType.BOTH) }
        if (!hasPermission(c)) {
            store.setMonitorStatus(if(active.isEmpty()) "No active location alarms" else "NOT REGISTERED: background/precise location missing")
            // Remove previous fences when permission still permits removal.
            client.removeGeofences(pending(c)).addOnCompleteListener { complete(c) }
            return
        }
        client.removeGeofences(pending(c)).addOnCompleteListener {
            if (active.isEmpty()) { store.setMonitorStatus("No active location alarms");complete(c);return@addOnCompleteListener }
            if (active.size > 50) { store.setMonitorStatus("ERROR: maximum 50 active location presets");complete(c);return@addOnCompleteListener }
            val request=GeofencingRequest.Builder().setInitialTrigger(0).apply {
                active.forEach { alarm ->
                    addGeofence(Geofence.Builder().setRequestId("in:${alarm.id}")
                        .setCircularRegion(alarm.latitude,alarm.longitude,alarm.radiusMetres.toFloat())
                        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                        .setNotificationResponsiveness(30_000)
                        .setExpirationDuration(Geofence.NEVER_EXPIRE).build())
                    if(alarm.direction==GeofenceDirection.EXIT) {
                        addGeofence(Geofence.Builder().setRequestId("leave:${alarm.id}")
                            .setCircularRegion(alarm.latitude,alarm.longitude,alarm.radiusMetres.toFloat())
                            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
                            .setNotificationResponsiveness(30_000)
                            .setExpirationDuration(Geofence.NEVER_EXPIRE).build())
                    }
                    addGeofence(Geofence.Builder().setRequestId("out:${alarm.id}")
                        .setCircularRegion(alarm.latitude,alarm.longitude,alarm.radiusMetres*1.25f)
                        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
                        .setNotificationResponsiveness(30_000)
                        .setExpirationDuration(Geofence.NEVER_EXPIRE).build())
                }
            }.build()
            try {
                client.addGeofences(request,pending(c))
                    .addOnSuccessListener { store.setMonitorStatus("Registered ${active.size} location preset(s)") }
                    .addOnFailureListener { err -> store.setMonitorStatus("Registration failed: ${err.message ?: "location service"}") }
                    .addOnCompleteListener { complete(c) }
            } catch (e: SecurityException) {
                store.setMonitorStatus("Registration failed: ${e.message}");complete(c)
            }
        }
    }
}
