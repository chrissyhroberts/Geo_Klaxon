package com.geoklaxon.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Credential-encrypted storage. Synchronous commits preserve events delivered to receivers. */
class AlarmStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("geo_klaxon", Context.MODE_PRIVATE)
    private val lock = companionLock

    companion object { private val companionLock = Any() }

    fun list(): List<AlarmPreset> = synchronized(lock) {
        val raw = prefs.getString("presets", "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        (0 until array.length()).mapNotNull { i -> runCatching { decode(array.getJSONObject(i)) }.getOrNull() }
    }
    fun get(id: String): AlarmPreset? = list().firstOrNull { it.id == id }

    fun put(value: AlarmPreset) = synchronized(lock) {
        require(value.valid()) { "Invalid alarm preset" }
        val all = list().toMutableList()
        val index = all.indexOfFirst { it.id == value.id }
        if (index < 0) all.add(value) else all[index] = value
        check(prefs.edit().putString("presets", JSONArray(all.map(::encode)).toString()).commit())
    }
    fun delete(id: String) = synchronized(lock) {
        val all = list().filterNot { it.id == id }
        check(prefs.edit().putString("presets", JSONArray(all.map(::encode)).toString()).commit())
        prefs.edit().remove("inside:$id").remove("last:$id").remove("snooze:$id").commit()
    }
    fun isInside(id: String) = prefs.getBoolean("inside:$id", false)
    fun setInside(id: String, value: Boolean) { prefs.edit().putBoolean("inside:$id", value).commit() }
    fun lastFired(id: String) = prefs.getLong("last:$id", 0L)
    fun setLastFired(id: String, time: Long) { prefs.edit().putLong("last:$id", time).commit() }
    fun snoozeAt(id: String) = prefs.getLong("snooze:$id", 0L)
    fun setSnoozeAt(id: String, time: Long) { prefs.edit().putLong("snooze:$id", time).commit() }
    fun getWidgetId(widgetId: Int) = prefs.getString("widget:$widgetId", null)
    fun setWidgetId(widgetId: Int, presetId: String) { prefs.edit().putString("widget:$widgetId", presetId).commit() }
    fun forgetWidget(widgetId: Int) { prefs.edit().remove("widget:$widgetId").apply() }
    fun monitorStatus(): String = prefs.getString("monitor_status", "Location monitoring not checked") ?: "Unknown"
    fun setMonitorStatus(s: String) { prefs.edit().putString("monitor_status", s).apply() }

    private fun encode(a: AlarmPreset) = JSONObject().apply {
        put("id",a.id); put("name",a.name); put("type",a.type.name); put("mode",a.mode.name)
        put("enabled",a.enabled);put("daysMask",a.daysMask);put("hour",a.hour);put("minute",a.minute)
        put("timerSeconds",a.timerSeconds);put("timerStartedAt",a.timerStartedAt)
        put("timerPaused",a.timerPaused);put("timerRemainingSeconds",a.timerRemainingSeconds)
        put("latitude",a.latitude);put("longitude",a.longitude);put("radiusMetres",a.radiusMetres)
        put("windowMinutes",a.windowMinutes);put("direction",a.direction.name);put("intensity",a.intensity.name)
        put("beepLevel",a.beepLevel);put("ringLevel",a.ringLevel);put("noiseLevel",a.noiseLevel)
        put("voiceLevel",a.voiceLevel);put("screamLevel",a.screamLevel);put("inverseSineLevel",a.inverseSineLevel)
        put("voiceMessage",a.voiceMessage)
        put("snoozeMinutes",a.snoozeMinutes);put("cooldownMinutes",a.cooldownMinutes)
    }
    private fun decode(o: JSONObject): AlarmPreset = AlarmPreset(
        id=o.getString("id"), name=o.optString("name","NEW ALARM"),
        type=runCatching { TriggerType.valueOf(o.optString("type")) }.getOrDefault(TriggerType.TIME),
        mode=runCatching { AlarmMode.valueOf(o.optString("mode")) }.getOrDefault(AlarmMode.ONE_SHOT),
        enabled=o.optBoolean("enabled",false), daysMask=o.optInt("daysMask",127),
        hour=o.optInt("hour",7),minute=o.optInt("minute",30),
        timerSeconds=o.optInt("timerSeconds",o.optInt("timerMinutes",30)*60).coerceIn(1,604800),
        timerStartedAt=o.optLong("timerStartedAt",0L),
        timerPaused=o.optBoolean("timerPaused",false),timerRemainingSeconds=o.optInt("timerRemainingSeconds",0).coerceIn(0,604800),
        latitude=o.optDouble("latitude",52.0),
        longitude=o.optDouble("longitude",-1.5),radiusMetres=o.optInt("radiusMetres",2000),
        windowMinutes=o.optInt("windowMinutes",120),
        direction=runCatching { GeofenceDirection.valueOf(o.optString("direction")) }.getOrDefault(GeofenceDirection.ENTER),
        intensity=runCatching { AlarmIntensity.valueOf(o.optString("intensity")) }.getOrDefault(AlarmIntensity.SERIOUS),
        beepLevel=o.optInt("beepLevel",65),ringLevel=o.optInt("ringLevel",75),
        noiseLevel=o.optInt("noiseLevel",0),voiceLevel=o.optInt("voiceLevel",85),
        screamLevel=o.optInt("screamLevel",0),inverseSineLevel=o.optInt("inverseSineLevel",0),
        voiceMessage=o.optString("voiceMessage","Arrgggh").let { message ->
            if(message.isBlank() || message=="WAKE UP! YOUR STOP IS COMING!") "Arrgggh" else message
        },
        snoozeMinutes=o.optInt("snoozeMinutes",5),cooldownMinutes=o.optInt("cooldownMinutes",15)
    )
}
