package com.geoklaxon.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.*
import android.net.Uri
import android.os.*
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlin.math.*
import kotlin.random.Random

/** One service owns the currently audible alarm; command tokens prevent stale notifications acting on a new alarm. */
class AlarmPlaybackService : Service() {
    companion object {
        private const val CHANNEL="geo_klaxon_ringing"
        private const val NOTIFICATION=8021
        private const val START="com.geoklaxon.START"
        private const val STOP="com.geoklaxon.STOP"
        private const val STOP_PRESET="com.geoklaxon.STOP_PRESET"
        private const val SNOOZE="com.geoklaxon.SNOOZE_PLAYBACK"
        private const val EXTRA_ID="preset_id"
        private const val EXTRA_TOKEN="session_token"
        private const val EXTRA_PRESET="preset"
        fun stopPreset(context: Context, id: String) {
            runCatching {
                context.startService(Intent(context,AlarmPlaybackService::class.java).apply {
                    action=STOP_PRESET;putExtra(EXTRA_ID,id)
                })
            }
        }
        fun start(context: Context, preset: AlarmPreset) {
            val i=Intent(context,AlarmPlaybackService::class.java).apply {
                action=START
                putExtra(EXTRA_ID,preset.id)
                putExtra(EXTRA_PRESET,preset)
            }
            try { ContextCompat.startForegroundService(context,i) }
            catch (e: Exception) {
                // Restrictive OEMs or Android background-start policy can refuse playback.
                AlarmStore(context).setMonitorStatus("Alarm service blocked: ${e.javaClass.simpleName}")
                showFallbackNotification(context,preset)
            }
        }
        private fun showFallbackNotification(c: Context,p: AlarmPreset) {
            val nm=c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL,"GeoKlaxon alarms",NotificationManager.IMPORTANCE_HIGH))
            val pi=PendingIntent.getActivity(c,0,Intent(c,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(NOTIFICATION,NotificationCompat.Builder(c,CHANNEL)
            .setSmallIcon(com.geoklaxon.app.R.drawable.ic_notification).setContentTitle("GEOKLAXON: ${p.name}")
                .setContentText("Tap to open. Android prevented alarm playback.")
                .setContentIntent(pi).setPriority(NotificationCompat.PRIORITY_MAX).build())
        }
    }
    private var active: AlarmPreset?=null
    private var focusRequest: AudioFocusRequest?=null
    private var token: String=""
    private var mixer: ToneMixer?=null
    private var tts: TextToSpeech?=null
    private var vibrator: Vibrator?=null
    private val handler=Handler(Looper.getMainLooper())
    private val speech=object: Runnable {
        override fun run() {
            val a=active ?: return
            if(a.voiceLevel>0) tts?.speak(a.voiceMessage,TextToSpeech.QUEUE_FLUSH,null,"geoklaxon_voice")
            handler.postDelayed(this,if(a.intensity==AlarmIntensity.SKUNK) 8000 else 13000)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,"GeoKlaxon ringing",NotificationManager.IMPORTANCE_HIGH).apply {
                description="Active alarm controls";setSound(null,null);enableVibration(false)
            })
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when(intent?.action) {
            START -> {
                val p=readPreset(intent) ?: run { stopSelf();return START_NOT_STICKY }
                stopPlayback()
                token=UUID.randomUUID().toString()
                active=p
                val notification=notification(p,token)
                try {
                    if(Build.VERSION.SDK_INT>=29) startForeground(NOTIFICATION,notification,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                    else startForeground(NOTIFICATION,notification)
                    val audioManager=getSystemService(AUDIO_SERVICE) as AudioManager
                    val attrs=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    focusRequest=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(attrs).setOnAudioFocusChangeListener { }.build()
                    audioManager.requestAudioFocus(focusRequest!!)
                    mixer=ToneMixer(p).also { it.start() }
                    if(p.voiceLevel>0) {
                        val session=token
                        tts=TextToSpeech(applicationContext) { result ->
                            if(result==TextToSpeech.SUCCESS && active?.id==p.id && token==session) {
                                tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                                tts?.setSpeechRate(if(p.intensity==AlarmIntensity.SKUNK) 1.25f else 1.0f)
                                handler.removeCallbacks(speech)
                                // If beeps are disabled, never leave the alarm silent at startup.
                                handler.postDelayed(speech,if(p.beepLevel>0) ToneMixer.VOICE_START_MS else 0L)
                            }
                        }
                    }
                    val vm=getSystemService(VIBRATOR_SERVICE) as Vibrator
                    vibrator=vm
                    if(vm.hasVibrator()) vm.vibrate(VibrationEffect.createWaveform(longArrayOf(0,600,300,600,900),0))
                } catch (err: Exception) {
                    AlarmStore(this).setMonitorStatus("Playback failed: ${err.javaClass.simpleName}")
                    stopSelf()
                }
            }
            STOP_PRESET -> {
                if(active?.id==intent.getStringExtra(EXTRA_ID)) {
                    stopPlayback();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
                } else if(active==null) stopSelf()
            }
            STOP, SNOOZE -> {
                if(intent.getStringExtra(EXTRA_TOKEN)==token && active!=null) {
                    val current=active!!
                    if(intent.action==SNOOZE) ClockScheduler.snooze(this,current)
                    else disarmAfterDismiss(current)
                    stopPlayback();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }
    private fun readPreset(intent: Intent): AlarmPreset? {
        val direct=if(Build.VERSION.SDK_INT>=33) {
            intent.getSerializableExtra(EXTRA_PRESET,AlarmPreset::class.java)
        } else {
            @Suppress("DEPRECATION")
            (intent.getSerializableExtra(EXTRA_PRESET) as? AlarmPreset)
        }
        return direct ?: AlarmStore(this).get(intent.getStringExtra(EXTRA_ID) ?: "")
    }
    private fun disarmAfterDismiss(current:AlarmPreset) {
        val store=AlarmStore(this)
        store.get(current.id)?.let { saved ->
            if(saved.enabled) store.put(saved.copy(enabled=false))
            ClockScheduler.cancel(this,current.id)
            ClockScheduler.cancelSnooze(this,current.id)
            GeofenceRegistrar.requestSync(this)
            LocationMonitorService.sync(this)
            TimedAlarmNotification.sync(this)
            WidgetProvider.refreshAll(this)
        }
    }
    private fun cmd(action:String,session:String): PendingIntent = PendingIntent.getService(this,0,
        Intent(this,AlarmPlaybackService::class.java).apply {
            this.action=action;data=Uri.parse("geoklaxon://$action/$session");putExtra(EXTRA_TOKEN,session)
        },PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun notification(a:AlarmPreset,session:String): Notification {
        val show=PendingIntent.getActivity(this,0,Intent(this,AlarmActivity::class.java).apply {
            data=Uri.parse("geoklaxon://ring/$session")
            putExtra("alarm_name",a.name);putExtra("alarm_token",session)
        },PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("⚡ GEOKLAXON! ${a.name}")
            .setContentText("WAKE UP! Tap to dismiss or snooze")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(show).setFullScreenIntent(show,true)
            .addAction(0,"SNOOZE",cmd(SNOOZE,session))
            .addAction(0,"DISMISS",cmd(STOP,session))
            .setOngoing(true).build()
    }
    private fun stopPlayback() {
        handler.removeCallbacks(speech)
        tts?.stop();tts?.shutdown();tts=null
        mixer?.stop();mixer=null
        vibrator?.cancel();vibrator=null
        focusRequest?.let { request ->
            runCatching {(getSystemService(AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(request)}
        }
        focusRequest=null
        active=null
    }
    override fun onDestroy() { stopPlayback();super.onDestroy() }
}

/** Generates a mixed PCM waveform, rather than serially playing competing ringtones. */
private class ToneMixer(private val preset:AlarmPreset) {
    companion object {
        const val VOICE_START_MS=8_000L
        private const val RING_START_MS=16_000L
        private const val NOISE_START_MS=24_000L
    }
    @Volatile private var running=false
    private var worker:Thread?=null
    private var track:AudioTrack?=null
    fun start() {
        running=true
        worker=Thread({
            val rate=22050
            val min=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT)
            if(min<=0)return@Thread
            val audio=AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(max(min,8192)).build()
            track=audio
            var tick=0L
            var brown=0.0
            val random=Random(System.currentTimeMillis())
            val samples=ShortArray(2048)
            val start=System.currentTimeMillis()
            try {
                audio.play()
                while(running) {
                    val elapsed=System.currentTimeMillis()-start
                    val escalation=when {
                        elapsed>=60_000->1.0
                        elapsed>=30_000->0.82
                        elapsed>=10_000->0.68
                        else->0.52
                    }
                    val beep=preset.beepLevel/100.0 * escalation
                    // Beeps are the normal opening cue, but every other enabled layer
                    // becomes an immediate fallback when the beep control is at zero.
                    val fallback= preset.beepLevel<=0
                    val ring=if(fallback || elapsed>=RING_START_MS) preset.ringLevel/100.0 * escalation else 0.0
                    val noise=if(fallback || elapsed>=NOISE_START_MS) preset.noiseLevel/100.0 * escalation else 0.0
                    val scream=if(fallback || elapsed>=VOICE_START_MS) preset.screamLevel/100.0 * escalation else 0.0
                    val inverseSine=if(fallback || elapsed>=RING_START_MS) preset.inverseSineLevel/100.0 * escalation else 0.0
                    for(i in samples.indices) {
                        val s=tick.toDouble()/rate
                        val burst=(tick%(rate*2L)) < (rate/3L)
                        val beepSound=if(burst) sin(2*Math.PI*880*s) * beep else 0.0
                        val frame=tick%(rate*4L)
                        val ringOn=(frame<rate/2 || (frame>=rate*3/4 && frame<rate*5/4))
                        val ringSound=if(ringOn) (sin(2*Math.PI*440*s)+sin(2*Math.PI*480*s))*0.4*ring else 0.0
                        // A deliberately synthetic scream: fast pitch wobble plus upper harmonics.
                        val screamPhase=2*Math.PI*(430*s + 115*sin(2*Math.PI*0.7*s))
                        val screamSound=(sin(screamPhase)+0.42*sin(screamPhase*2.01)+0.18*sin(screamPhase*3.03))*0.36*scream
                        // arcsin(sin()) gives the abrasive, backwards-sine / folded-wave character.
                        val folded=asin(sin(2*Math.PI*155*s))*(2/Math.PI)
                        val inverseSound=folded*0.55*inverseSine
                        brown=(brown + (random.nextDouble()-0.5)*0.09).coerceIn(-1.0,1.0)*0.997
                        val combined=beepSound*0.43+ringSound*0.45+screamSound+inverseSound+brown*noise*0.8
                        // Keep the staged build loud without hard-clipping when all layers peak together.
                        val limited=tanh(combined)
                        samples[i]=(limited*Short.MAX_VALUE).toInt().toShort()
                        tick++
                    }
                    if(audio.write(samples,0,samples.size)<=0)break
                }
            } catch (_: Exception) { /* Hardware route may disappear during a Bluetooth transition. */ }
            finally { runCatching { audio.stop() }; audio.release() }
        },"GeoKlaxon-Audio").apply { isDaemon=true;start() }
    }
    fun stop() { running=false; runCatching{ track?.pause();track?.flush() } }
}
