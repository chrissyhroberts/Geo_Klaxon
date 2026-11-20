package com.geoklaxon.app

import android.Manifest
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

private val Ink=Color(0xFF160725)
private val Dark=Color(0xFF291043)
private val HotPink=Color(0xFFFF39B9)
private val Cyan=Color(0xFF0BEBE2)
private val Acid=Color(0xFFFFEC00)
private val Lime=Color(0xFF7AFF39)
private val White=Color(0xFFF9F0FF)
private fun displayTrigger(type:TriggerType):String = when(type) {
    TriggerType.BOTH -> "TIME & PLACE"
    TriggerType.TIMER -> "TIMER"
    TriggerType.TIME -> "TIME"
    TriggerType.PLACE -> "PLACE"
}

class MainActivity : ComponentActivity() {
    private val stateVersion=mutableIntStateOf(0)
    private var lastGeoPermission: Boolean? = null
    private val permissionLauncher=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        stateVersion.intValue++
        GeofenceRegistrar.requestSync(this)
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        ClockScheduler.restore(this)
        GeofenceRegistrar.requestSync(this)
        LocationMonitorService.sync(this)
        TimedAlarmNotification.sync(this)
        val activity = this
        setContent {
            val change=stateVersion.intValue
            var screen by remember { mutableStateOf("home") }
            var editing by remember { mutableStateOf(AlarmPreset()) }
            val presets=remember(change,screen) { AlarmStore(activity).list() }
            MaterialTheme(colorScheme=darkColorScheme(primary=HotPink,secondary=Cyan,tertiary=Acid,
                background=Ink,surface=Dark,onPrimary=Ink,onSurface=White)) {
                Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Ink,Color(0xFF25103C),Ink)))) {
                    when(screen) {
                        "home" -> Dashboard(presets,
                            onCreate={type->createNewPreset(type) { preset -> editing=preset;screen="edit" }},
                            onEdit={editing=it;screen="edit"},
                            onToggle={p->
                                if(!p.enabled) AlarmStore(activity).setInside(p.id,false)
                                AlarmStore(activity).put(p.copy(enabled=!p.enabled))
                                AlarmCoordinator.changed(activity,p.id);stateVersion.intValue++
                            },
                            onAddMinute={p->{
                                ClockScheduler.addTimerMinute(activity,p)
                                TimedAlarmNotification.sync(activity)
                                WidgetProvider.refreshAll(activity)
                                stateVersion.intValue++
                            }},
                            onTest={AlarmPlaybackService.start(activity,it)},
                            onSettings={screen="settings"})
                        "settings" -> SettingsPanel(
                            warnings=AlarmCoordinator.status(activity),
                            onBack={screen="home"},
                            onPermissions={openPermissions()})
                        "edit" -> Editor(initial=editing,onBack={screen="home"},onSave={p->
                            // Saving an alarm is also the deliberate re-arm gesture,
                            // including after a one-shot alarm has fired.
                            val saved=p.copy(enabled=true,mode=if(p.type==TriggerType.TIMER) AlarmMode.ONE_SHOT else p.mode,
                                timerStartedAt=if(p.type==TriggerType.TIMER) System.currentTimeMillis() else p.timerStartedAt,
                                timerPaused=if(p.type==TriggerType.TIMER) false else p.timerPaused,
                                timerRemainingSeconds=if(p.type==TriggerType.TIMER) 0 else p.timerRemainingSeconds)
                            if(editing.latitude!=saved.latitude || editing.longitude!=saved.longitude || editing.radiusMetres!=saved.radiusMetres)
                                AlarmStore(activity).setInside(p.id,false)
                            AlarmStore(activity).put(saved)
                            AlarmCoordinator.changed(activity,saved.id)
                            stateVersion.intValue++
                            screen="home"
                        },onDelete={p->
                            ClockScheduler.cancel(activity,p.id)
                            ClockScheduler.cancelSnooze(activity,p.id)
                            AlarmStore(activity).delete(p.id)
                            AlarmCoordinator.changed(activity,p.id)
                            stateVersion.intValue++
                            screen="home"
                        },onRequestLocationPermission={openPermissions()})
                    }
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        val allowed=GeofenceRegistrar.hasPermission(this)
        if (lastGeoPermission != allowed) {
            lastGeoPermission=allowed
            GeofenceRegistrar.requestSync(this)
            LocationMonitorService.sync(this)
        }
        stateVersion.intValue++
        WidgetProvider.refreshAll(this)
    }
    private fun openPermissions() {
        val needed=mutableListOf<String>()
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if(needed.isNotEmpty()) {permissionLauncher.launch(needed.toTypedArray());return}
        if(Build.VERSION.SDK_INT>=29 && ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_BACKGROUND_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            if(Build.VERSION.SDK_INT==29) permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))
            return
        }
        if(Build.VERSION.SDK_INT>=31 && !getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:$packageName")))
            return
        }
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))
    }
    private fun createNewPreset(type:TriggerType,onReady:(AlarmPreset)->Unit) {
        val base=AlarmPreset(type=type,enabled=true,
            mode=if(type==TriggerType.TIMER) AlarmMode.ONE_SHOT else AlarmMode.REPEAT)
        if(type==TriggerType.TIME || type==TriggerType.TIMER) {
            onReady(base)
            return
        }
        val hasLocation=ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
        if(!hasLocation) {
            // Do not present the arbitrary model default as if it were the user's location.
            onReady(base.copy(latitude=0.0,longitude=0.0))
            openPermissions()
            return
        }
        runCatching {
            LocationServices.getFusedLocationProviderClient(this).lastLocation
                .addOnSuccessListener { location ->
                    onReady(if(location==null) base.copy(latitude=0.0,longitude=0.0)
                        else base.copy(latitude=location.latitude,longitude=location.longitude))
                }
                .addOnFailureListener { onReady(base.copy(latitude=0.0,longitude=0.0)) }
        }.onFailure { onReady(base.copy(latitude=0.0,longitude=0.0)) }
    }
}

@Composable
private fun ArcadeHeading(subtitle:String) {
    Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.fillMaxWidth().padding(top=18.dp,bottom=14.dp)) {
        Image(painter=painterResource(R.drawable.mascot_boombox),contentDescription="Cartoon neon boombox mascot",
            modifier=Modifier.size(width=130.dp,height=88.dp))
        Text("⚡ GEO KLAXON! ⚡",fontSize=30.sp,fontWeight=FontWeight.Black,color=Acid)
        Text(subtitle,fontSize=13.sp,fontWeight=FontWeight.Bold,color=Cyan,letterSpacing=1.sp)
    }
}
@Composable
private fun NeonCard(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
    Surface(modifier=modifier,border=BorderStroke(1.dp,HotPink.copy(alpha=.65f)),shape=RoundedCornerShape(16.dp),color=Dark) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp),content=content)
    }
}

@Composable
private fun Dashboard(
    presets:List<AlarmPreset>,
    onCreate:(TriggerType)->Unit,onEdit:(AlarmPreset)->Unit,onToggle:(AlarmPreset)->Unit,
    onAddMinute:(AlarmPreset)->Unit,onTest:(AlarmPreset)->Unit,onSettings:()->Unit
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val runningTimers=presets.filter { it.enabled && it.type==TriggerType.TIMER }
    LaunchedEffect(runningTimers.map { it.id to it.timerStartedAt }) {
        while(true) {
            now=System.currentTimeMillis()
            delay(1000L)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=16.dp).padding(bottom=32.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
            IconButton(onClick=onSettings) {
                Icon(Icons.Filled.Settings,contentDescription="Open settings",tint=Cyan)
            }
        }
        ArcadeHeading("TIME • PLACE • MAXIMUM NOISE")
        Surface(
            modifier=Modifier.fillMaxWidth(),
            shape=RoundedCornerShape(20.dp),
            color=Color(0xFF47105B),
            border=BorderStroke(2.dp,HotPink)
        ) {
            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("NEW ALARM",fontSize=24.sp,fontWeight=FontWeight.Black,color=Acid)
                Text("Choose how GeoKlaxon should wake you up.",fontSize=13.sp,color=White)
                Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                listOf(TriggerType.TIME to "⏰ TIME",TriggerType.TIMER to "⏱ TIMER",TriggerType.PLACE to "📍 PLACE",TriggerType.BOTH to "⚡ TIME & PLACE").forEach { (type,label)->
                    Button(onClick={onCreate(type)},modifier=Modifier.weight(1f).height(58.dp),
                        contentPadding=PaddingValues(horizontal=4.dp,vertical=10.dp),
                        colors=ButtonDefaults.buttonColors(containerColor=if(type==TriggerType.PLACE) Cyan else HotPink)) {
                        Text(label,color=Ink,fontWeight=FontWeight.Black,fontSize=12.sp)
                    }
                }
                }
            }
        }
        Spacer(Modifier.height(15.dp))
        if(runningTimers.isNotEmpty()) {
            Text("RUNNING TIMERS",color=Acid,fontWeight=FontWeight.Black,fontSize=21.sp)
            Spacer(Modifier.height(8.dp))
            runningTimers.forEach { timer ->
                RunningTimerCard(timer,now,onAddMinute)
                Spacer(Modifier.height(8.dp))
            }
        }
        Text("YOUR PRESETS",color=Cyan,fontWeight=FontWeight.Black,fontSize=21.sp)
        Spacer(Modifier.height(8.dp))
        if(presets.isEmpty()) NeonCard {
            Text("NO ALARMS YET",color=Acid,fontWeight=FontWeight.Black)
            Text("Start with TIME for a seven-day wake-up call, or PLACE for a station warning.")
        }
        presets.forEach { a->
            NeonCard(Modifier.fillMaxWidth().padding(bottom=10.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.name.uppercase(),fontWeight=FontWeight.Black,fontSize=19.sp,color=if(a.enabled)Lime else White)
                        Text("${displayTrigger(a.type)} • ${a.direction} • ${a.mode} • ${a.intensity}",color=Cyan,fontSize=11.sp)
                        Text(if(a.type==TriggerType.TIME) "%02d:%02d".format(a.hour,a.minute)
                            else if(a.type==TriggerType.TIMER) formatTimer(a.timerSeconds)
                            else if(a.type==TriggerType.PLACE) "${a.radiusMetres/1000.0} km radius"
                            else "%02d:%02d + %d min window • %.1f km".format(a.hour,a.minute,a.windowMinutes,a.radiusMetres/1000.0),
                            fontSize=15.sp,color=Acid)
                    }
                    Switch(checked=a.enabled,onCheckedChange={onToggle(a)},
                        colors=SwitchDefaults.colors(checkedTrackColor=Lime,checkedThumbColor=Ink))
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={onEdit(a)},modifier=Modifier.weight(1f)) {Text("EDIT",color=Cyan)}
                    OutlinedButton(onClick={onTest(a)},modifier=Modifier.weight(1f)) {Text("TEST SOUND",color=Acid)}
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("WIDGETS: long-press your home screen → Widgets → GeoKlaxon. Choose a saved alarm for each widget.",color=Color.LightGray,fontSize=12.sp)
    }
}

@Composable
private fun RunningTimerCard(timer:AlarmPreset,now:Long,onAddMinute:(AlarmPreset)->Unit) {
    val remaining=timerRemaining(timer,now)
    NeonCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(timer.name.uppercase(),color=Lime,fontWeight=FontWeight.Black,fontSize=18.sp)
                Text(if(timer.timerPaused) "PAUSED" else "RUNNING",color=Cyan,fontWeight=FontWeight.Bold,fontSize=11.sp)
                Text(formatCountdown(remaining),color=Acid,fontWeight=FontWeight.Black,fontSize=28.sp)
            }
            Button(onClick={onAddMinute(timer)},colors=ButtonDefaults.buttonColors(containerColor=HotPink)) {
                Text("+1 MIN",color=Ink,fontWeight=FontWeight.Black)
            }
        }
    }
}

private fun timerRemaining(timer:AlarmPreset,now:Long):Int {
    if(timer.timerPaused) return timer.timerRemainingSeconds
    val started=if(timer.timerStartedAt>0L) timer.timerStartedAt else now
    val duration=if(timer.timerRemainingSeconds>0) timer.timerRemainingSeconds else timer.timerSeconds
    return ((started+duration*1000L-now+999L)/1000L).toInt().coerceAtLeast(0)
}

private fun formatCountdown(seconds:Int):String {
    val days=seconds/86400
    val hours=(seconds%86400)/3600
    val minutes=(seconds%3600)/60
    val secs=seconds%60
    return if(days>0) "%dd %02d:%02d:%02d".format(java.util.Locale.getDefault(),days,hours,minutes,secs)
    else "%02d:%02d:%02d".format(java.util.Locale.getDefault(),hours,minutes,secs)
}

@Composable
private fun SettingsPanel(warnings:List<String>,onBack:()->Unit,onPermissions:()->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=16.dp).padding(bottom=32.dp)) {
        ArcadeHeading("SYSTEM STATUS & PERMISSIONS")
        OutlinedButton(onClick=onBack) { Text("← BACK",color=Cyan) }
        Spacer(Modifier.height(12.dp))
        NeonCard {
            Text("SYSTEM CHECK",fontWeight=FontWeight.Black,fontSize=20.sp,color=Acid)
            if(warnings.isEmpty()) {
                Text("Everything looks ready.",color=Lime)
            } else {
                warnings.forEach { Text("• $it",color=White,fontSize=13.sp) }
            }
            OutlinedButton(onClick=onPermissions) { Text("OPEN PERMISSIONS",color=Cyan) }
            Text("GeoKlaxon checks location, notifications, exact alarms and geofence registration here. Android may delay background geofences by several minutes.",fontSize=11.sp,color=Color.LightGray)
        }
    }
}

@Composable
private fun Editor(initial:AlarmPreset,onBack:()->Unit,onSave:(AlarmPreset)->Unit,onDelete:(AlarmPreset)->Unit,onRequestLocationPermission:()->Unit) {
    val context=LocalContext.current
    var a by remember(initial.id){ mutableStateOf(initial) }
    var postcode by remember(initial.id){mutableStateOf("")}
    var lookupStatus by remember(initial.id){mutableStateOf(
        if((initial.type==TriggerType.PLACE || initial.type==TriggerType.BOTH) && initial.latitude==0.0 && initial.longitude==0.0)
            "Current position unavailable — open the map and choose a point"
        else "Offline map: tap to place marker or enter coordinates"
    )}
    var latString by remember(initial.id){mutableStateOf("%.5f".format(java.util.Locale.US,initial.latitude))}
    var lonString by remember(initial.id){mutableStateOf("%.5f".format(java.util.Locale.US,initial.longitude))}
    var mapExpanded by remember(initial.id){mutableStateOf(false)}
    var recenterToken by remember(initial.id){mutableIntStateOf(0)}
    var locationStatus by remember(initial.id){mutableStateOf("")}
    var timePickerOpen by remember(initial.id){mutableStateOf(false)}
    var searchResults by remember(initial.id){mutableStateOf(emptyList<UKPlaceResult>())}
    var error by remember(initial.id){mutableStateOf("")}
    var voiceText by remember(initial.id){mutableStateOf(TextFieldValue(initial.voiceMessage))}
    var voiceHasFocus by remember(initial.id){mutableStateOf(false)}
    LaunchedEffect(voiceHasFocus) {
        if(voiceHasFocus) {
            delay(50L)
            voiceText=voiceText.copy(selection=TextRange(0,voiceText.text.length))
        }
    }
    var confirmDelete by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val applyPoint:(LatLon)->Unit={ point ->
        a=a.copy(latitude=point.lat,longitude=point.lon)
        latString="%.5f".format(java.util.Locale.US,point.lat)
        lonString="%.5f".format(java.util.Locale.US,point.lon)
    }
    val useCurrentLocation:()->Unit={
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            locationStatus="Location permission is needed for current position"
            onRequestLocationPermission()
        } else {
            locationStatus="Finding current position…"
            runCatching {
                LocationServices.getFusedLocationProviderClient(context).lastLocation
                    .addOnSuccessListener { location ->
                        if(location==null) locationStatus="Position unavailable; try again outdoors"
                        else { applyPoint(LatLon(location.latitude,location.longitude));recenterToken++;locationStatus="Current position selected" }
                    }
                    .addOnFailureListener { locationStatus="Could not read current position" }
            }.onFailure { locationStatus="Location permission is needed for current position" }
            Unit
        }
    }
    if(mapExpanded) {
        FullScreenOsmMap(selected=LatLon(a.latitude,a.longitude),radius=a.radiusMetres,
            recenterToken=recenterToken,onBack={mapExpanded=false},onSelect=applyPoint,
            onUseCurrentLocation=useCurrentLocation,locationStatus=locationStatus,
            onRadiusChange={a=a.copy(radiusMetres=((it/50).roundToInt()*50).coerceIn(50,10000))})
        return
    }
    DisposableEffect(timePickerOpen) {
        val dialog=if(timePickerOpen) TimePickerDialog(context,{ _,hour,minute ->
                a=a.copy(hour=hour,minute=minute)
                timePickerOpen=false
            },a.hour,a.minute,true) else null
        dialog?.setOnDismissListener { timePickerOpen=false }
        dialog?.show()
        onDispose { dialog?.dismiss() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=16.dp).padding(bottom=40.dp),
        verticalArrangement=Arrangement.spacedBy(10.dp)) {
        ArcadeHeading("PROGRAM YOUR NOISE MACHINE")
        OutlinedButton(onClick=onBack){Text("← BACK",color=Cyan)}
        NeonCard {
            Text("1  WHAT SETS IT OFF?",fontWeight=FontWeight.Black,color=Acid)
            Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                TriggerType.entries.forEach { type->FilterChip(selected=a.type==type,onClick={a=a.copy(type=type)},label={Text(type.name)},modifier=Modifier.weight(1f))}
            }
            Text("Name",color=Cyan)
            OutlinedTextField(value=a.name,onValueChange={a=a.copy(name=it.take(60))},modifier=Modifier.fillMaxWidth(),singleLine=true)
            Text("Operating mode",color=Cyan)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                (if(a.type==TriggerType.TIMER) listOf(AlarmMode.ONE_SHOT) else AlarmMode.entries).forEach { mode->
                    FilterChip(selected=a.mode==mode,onClick={a=a.copy(mode=mode)},label={Text(if(mode==AlarmMode.REPEAT)"ALWAYS ON" else "ONE SHOT")})
                }
            }
            Text(if(a.mode==AlarmMode.REPEAT) "Repeat alarms remain armed. Location alarms rearm after leaving beyond 125% of the radius." else "One-shot alarms automatically disarm after a successful trigger.",fontSize=11.sp,color=Color.LightGray)
        }
        if(a.type==TriggerType.TIME || a.type==TriggerType.BOTH) NeonCard {
            Text(if(a.type==TriggerType.BOTH) "2  TIME & PLACE WINDOW" else "2  CLOCK & SEVEN-DAY PROGRAMME",fontWeight=FontWeight.Black,color=Acid)
            Text(if(a.type==TriggerType.BOTH) "Alert only when the selected direction occurs during this time window." else "Choose the clock time for this alarm.",fontSize=12.sp,color=Color.LightGray)
            Button(onClick={timePickerOpen=true},modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=HotPink)) {
                Text(if(a.type==TriggerType.BOTH) "WINDOW START  %02d:%02d".format(a.hour,a.minute) else "ALARM TIME  %02d:%02d".format(a.hour,a.minute),color=Ink,fontWeight=FontWeight.Black,fontSize=18.sp)
            }
            Row(horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                listOf("M","T","W","T","F","S","S").forEachIndexed{idx,day->
                    val bit=1 shl idx
                    FilterChip(selected=(a.daysMask and bit)!=0,
                        onClick={a=a.copy(daysMask=a.daysMask xor bit)},label={Text(day,fontSize=11.sp)},modifier=Modifier.weight(1f))
                }
            }
            Text("Selected days apply every week. For one-shot alarms, the first matching day triggers once.",fontSize=11.sp,color=Color.LightGray)
            if(a.type==TriggerType.BOTH) {
                Text("Location must also be INSIDE the fence during this time window.",color=Cyan,fontSize=12.sp)
                Text("Window: ${a.windowMinutes} minutes after %02d:%02d".format(a.hour,a.minute))
                Slider(value=a.windowMinutes.toFloat(),onValueChange={a=a.copy(windowMinutes=(it/5).roundToInt().coerceIn(1,72)*5)},valueRange=5f..360f)
            }
        }
        if(a.type==TriggerType.TIMER) NeonCard {
            Text("2  TIMER",fontWeight=FontWeight.Black,color=Acid)
            Text("Start a countdown from when you save or re-arm it.",fontSize=12.sp,color=Color.LightGray)
            Text("Duration: ${formatTimer(a.timerSeconds)}",color=Cyan,fontSize=18.sp,fontWeight=FontWeight.Bold)
            TimerDurationPickers(seconds=a.timerSeconds,onChange={a=a.copy(timerSeconds=it)})
        }
        if(a.type==TriggerType.PLACE || a.type==TriggerType.BOTH) NeonCard {
            Text("3  DESTINATION & RINGFENCE",fontWeight=FontWeight.Black,color=Acid)
            Text("Alert direction",color=Cyan,fontSize=12.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=a.direction==GeofenceDirection.ENTER,onClick={a=a.copy(direction=GeofenceDirection.ENTER)},label={Text("ARRIVE / ENTER")})
                FilterChip(selected=a.direction==GeofenceDirection.EXIT,onClick={a=a.copy(direction=GeofenceDirection.EXIT)},label={Text("LEAVE / EXIT")})
            }
            Text(if(a.direction==GeofenceDirection.EXIT)
                "Leave alerts require GeoKlaxon to observe an entry before the exit can trigger."
                else "Arrive alerts trigger when the device enters the destination zone.",fontSize=11.sp,color=Color.LightGray)
            Text("OpenStreetMap • towns • villages • stations • tap to place",color=Cyan,fontSize=12.sp)
            OsmMap(selected=LatLon(a.latitude,a.longitude),radius=a.radiusMetres,onSelect={mapExpanded=true},
                gesturesEnabled=false,modifier=Modifier
                .fillMaxWidth()
                .widthIn(max=520.dp)
                .height(220.dp)
                .align(Alignment.CenterHorizontally))
            Text("© OpenStreetMap contributors · OpenFreeMap",fontSize=10.sp,color=Color.LightGray)
            OutlinedButton(onClick={mapExpanded=true},modifier=Modifier.fillMaxWidth()) {
                Text("OPEN FULL-SCREEN MAP",color=Cyan,fontWeight=FontWeight.Bold)
            }
            Text("Radius: ${a.radiusMetres/1000.0} km",fontSize=20.sp,fontWeight=FontWeight.Black,color=Acid)
            Slider(value=a.radiusMetres.toFloat(),onValueChange={a=a.copy(radiusMetres=((it/50).roundToInt()*50).coerceIn(50,10000))},valueRange=50f..10000f)
            Text("UK postcode / place lookup (internet only for search)",fontSize=12.sp,color=Cyan)
            OutlinedTextField(value=postcode,onValueChange={postcode=it},modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("e.g. YO1 6HT or York")})
            Button(onClick={ scope.launch {
                lookupStatus="Searching…"
                searchResults=emptyList()
                try { val found=FreeUKLookup.lookup(postcode)
                    if(found.isEmpty())lookupStatus="No match; try a full postcode or tap map"
                    else if(found.size==1) {
                        searchResults=emptyList()
                        val result=found.first();a=a.copy(latitude=result.point.lat,longitude=result.point.lon)
                        latString="%.5f".format(java.util.Locale.US,result.point.lat)
                        lonString="%.5f".format(java.util.Locale.US,result.point.lon)
                        lookupStatus="${result.label}${if(result.context.isNotBlank())" — ${result.context}" else ""}"
                    } else {
                        searchResults=found
                        lookupStatus="${found.size} matches — choose the right one below"
                    }
                }catch(ex:Exception){lookupStatus="Search unavailable: ${ex.javaClass.simpleName}. Offline coordinates still work."}
            } }) { Text("SEARCH") }
            Text(lookupStatus,fontSize=11.sp,color=Cyan)
            searchResults.forEach { result ->
                OutlinedButton(onClick={
                    a=a.copy(latitude=result.point.lat,longitude=result.point.lon)
                    latString="%.5f".format(java.util.Locale.US,result.point.lat)
                    lonString="%.5f".format(java.util.Locale.US,result.point.lon)
                    lookupStatus="${result.label}${if(result.context.isNotBlank())" — ${result.context}" else ""}"
                    searchResults=emptyList()
                },modifier=Modifier.fillMaxWidth()) {
                    Column(horizontalAlignment=Alignment.Start,modifier=Modifier.fillMaxWidth()) {
                        Text(result.label,color=Acid,fontWeight=FontWeight.Bold)
                        if(result.context.isNotBlank())Text(result.context,color=Cyan,fontSize=11.sp)
                    }
                }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value=latString,onValueChange={s->latString=s;s.toDoubleOrNull()?.let{if(it in -85.0..85.0)a=a.copy(latitude=it)}},
                    label={Text("Latitude")},modifier=Modifier.weight(1f),singleLine=true)
                OutlinedTextField(value=lonString,onValueChange={s->lonString=s;s.toDoubleOrNull()?.let{if(it in -180.0..180.0)a=a.copy(longitude=it)}},
                    label={Text("Longitude")},modifier=Modifier.weight(1f),singleLine=true)
            }
            Text("Geo alarms may arrive minutes late on Android. For trains, start with a 2–10 km warning radius, not 50 m.",color=Color.LightGray,fontSize=11.sp)
        }
        NeonCard {
            Text("4  THE CACOPHONY ENGINE",fontWeight=FontWeight.Black,color=Acid)
            Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                AlarmIntensity.entries.forEach{style->
                    FilterChip(selected=a.intensity==style,onClick={
                        a=when(style) {
                            AlarmIntensity.SUBTLE->a.copy(intensity=style,beepLevel=30,ringLevel=0,noiseLevel=0,voiceLevel=35,screamLevel=0,inverseSineLevel=0)
                            AlarmIntensity.SERIOUS->a.copy(intensity=style,beepLevel=65,ringLevel=75,noiseLevel=0,voiceLevel=85,screamLevel=35,inverseSineLevel=25)
                            AlarmIntensity.SKUNK->a.copy(intensity=style,beepLevel=95,ringLevel=95,noiseLevel=75,voiceLevel=100,screamLevel=90,inverseSineLevel=95)
                        }
                    },label={Text(if(style==AlarmIntensity.SKUNK)"SKUNK!" else style.name,fontSize=11.sp)},modifier=Modifier.weight(1f))
                }
            }
            MixerSlider("BEEP",a.beepLevel){a=a.copy(beepLevel=it)}
            MixerSlider("RING",a.ringLevel){a=a.copy(ringLevel=it)}
            MixerSlider("BROWN NOISE",a.noiseLevel){a=a.copy(noiseLevel=it)}
            MixerSlider("VOICE",a.voiceLevel){a=a.copy(voiceLevel=it)}
            MixerSlider("SCREAM",a.screamLevel){a=a.copy(screamLevel=it)}
            MixerSlider("INVERSE SINE",a.inverseSineLevel){a=a.copy(inverseSineLevel=it)}
            OutlinedTextField(
                value=voiceText,
                onValueChange={value->voiceText=value.copy(text=value.text.take(250));a=a.copy(voiceMessage=value.text.take(250))},
                modifier=Modifier.fillMaxWidth().onFocusChanged { focus -> voiceHasFocus=focus.isFocused },
                label={Text("Spoken warning")}
            )
            Text("Phone alarm volume still applies. This app does not bypass Do Not Disturb or override Bluetooth routing.",fontSize=11.sp,color=Color.LightGray)
        }
        NeonCard {
            Text("5  SNOOZE & REPEAT PROTECTION",fontWeight=FontWeight.Black,color=Acid)
            Text("Snooze: ${a.snoozeMinutes} min",color=Cyan)
            Slider(value=a.snoozeMinutes.toFloat(),onValueChange={a=a.copy(snoozeMinutes=it.roundToInt().coerceIn(1,30))},valueRange=1f..30f)
            if(a.type==TriggerType.PLACE || a.type==TriggerType.BOTH) {
                Text("Retrigger cooldown: ${a.cooldownMinutes} min",color=Cyan)
                Slider(value=a.cooldownMinutes.toFloat(),onValueChange={a=a.copy(cooldownMinutes=it.roundToInt().coerceIn(1,180))},valueRange=1f..180f)
            }
        }
        if(error.isNotEmpty())Text(error,color=Acid)
        Button(onClick={
            val lat=latString.toDoubleOrNull()
            val lon=lonString.toDoubleOrNull()
            if((a.type==TriggerType.PLACE || a.type==TriggerType.BOTH) && (lat==null || lon==null || lat !in -85.0..85.0 || lon !in -180.0..180.0))
                error="Enter valid latitude and longitude"
            else if(!a.valid()) error="Check name, at least one weekday and coordinates"
            else onSave(a.copy(latitude=lat ?: a.latitude,longitude=lon ?: a.longitude))
        },modifier=Modifier.fillMaxWidth().height(60.dp),colors=ButtonDefaults.buttonColors(containerColor=Lime)) {
            Text("SAVE THE KLAXON!",color=Ink,fontWeight=FontWeight.Black,fontSize=20.sp)
        }
        OutlinedButton(onClick={AlarmPlaybackService.start(context,a)},modifier=Modifier.fillMaxWidth().height(54.dp)) {
            Text("TEST THIS ALARM",color=Acid,fontWeight=FontWeight.Black,fontSize=17.sp)
        }
        if(AlarmStore(context).get(a.id)!=null) OutlinedButton(onClick={confirmDelete=true},modifier=Modifier.fillMaxWidth()) {Text("DELETE ALARM",color=HotPink)}
    }
    if(confirmDelete) AlertDialog(onDismissRequest={confirmDelete=false},title={Text("Delete ${a.name}?")},
        text={Text("Removes schedules and geofences for this alarm.")},confirmButton={TextButton(onClick={onDelete(a)}){Text("DELETE")}},
        dismissButton={TextButton(onClick={confirmDelete=false}){Text("CANCEL")}})
}

private fun formatTimer(totalSeconds:Int):String {
    val days=totalSeconds/86400
    val hours=(totalSeconds%86400)/3600
    val minutes=(totalSeconds%3600)/60
    val seconds=totalSeconds%60
    return buildString {
        if(days>0) append("${days}d ")
        if(hours>0 || days>0) append("${hours}h ")
        if(minutes>0 || hours>0 || days>0) append("${minutes}m ")
        append("${seconds}s")
    }.trim()
}

@Composable
private fun TimerDurationPickers(seconds:Int,onChange:(Int)->Unit) {
    val safe=seconds.coerceIn(0,604800)
    var days by remember { mutableIntStateOf(safe/86400) }
    var hours by remember { mutableIntStateOf((safe%86400)/3600) }
    var minutes by remember { mutableIntStateOf((safe%3600)/60) }
    var secs by remember { mutableIntStateOf(safe%60) }
    fun emit() { onChange((days*86400+hours*3600+minutes*60+secs).coerceIn(0,604800)) }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
        TimerWheel("DAYS",days,0,7,Modifier.weight(1f)) { value -> days=value;emit() }
        TimerWheel("HOURS",hours,0,23,Modifier.weight(1f)) { value -> hours=value;emit() }
        TimerWheel("MIN",minutes,0,59,Modifier.weight(1f)) { value -> minutes=value;emit() }
        TimerWheel("SEC",secs,0,59,Modifier.weight(1f)) { value -> secs=value;emit() }
    }
}

@Composable
private fun TimerWheel(label:String,value:Int,min:Int,max:Int,modifier:Modifier,onValueChange:(Int)->Unit) {
    val state=rememberLazyListState(initialFirstVisibleItemIndex=(value-min).coerceIn(0,max-min))
    LaunchedEffect(value) {
        val target=(value-min).coerceIn(0,max-min)
        if(state.firstVisibleItemIndex!=target) state.scrollToItem(target)
    }
    LaunchedEffect(Unit) {
        var wasScrolling=false
        snapshotFlow { state.isScrollInProgress }.distinctUntilChanged().collect { scrolling ->
            if(scrolling) {
                wasScrolling=true
            } else if(wasScrolling) {
                val viewport=state.layoutInfo.viewportStartOffset..state.layoutInfo.viewportEndOffset
                val centre=(viewport.first+viewport.last)/2
                val selected=state.layoutInfo.visibleItemsInfo.minByOrNull {
                    kotlin.math.abs((it.offset+it.size/2)-centre)
                }?.index ?: state.firstVisibleItemIndex
                onValueChange((min+selected).coerceIn(min,max))
                wasScrolling=false
            }
        }
    }
    Column(modifier.background(Dark,RoundedCornerShape(12.dp)),horizontalAlignment=Alignment.CenterHorizontally) {
        Text(label,color=Cyan,fontSize=10.sp,fontWeight=FontWeight.Bold)
        Box(Modifier.fillMaxWidth().height(96.dp)) {
            LazyColumn(
                state=state,
                modifier=Modifier.fillMaxSize(),
                flingBehavior=rememberSnapFlingBehavior(state),
                contentPadding=PaddingValues(vertical=32.dp)
            ) {
                items(max-min+1) { index ->
                    Box(Modifier.fillMaxWidth().height(32.dp),contentAlignment=Alignment.Center) {
                        Text("%02d".format(min+index),color=White,fontSize=22.sp,fontWeight=FontWeight.Black)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(32.dp).align(Alignment.Center)
                .border(1.dp,Cyan,RoundedCornerShape(6.dp)))
        }
    }
}

@Composable
private fun FullScreenOsmMap(
    selected:LatLon,
    radius:Int,
    recenterToken:Int,
    onBack:()->Unit,
    onSelect:(LatLon)->Unit,
    onUseCurrentLocation:()->Unit,
    locationStatus:String,
    onRadiusChange:(Float)->Unit
) {
    BackHandler(onBack=onBack)
    Column(Modifier.fillMaxSize().background(Ink).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick=onBack) { Text("← BACK",color=Cyan) }
            Text("CHOOSE DESTINATION",modifier=Modifier.weight(1f),color=Acid,fontWeight=FontWeight.Black,fontSize=18.sp)
            Button(onClick=onBack,colors=ButtonDefaults.buttonColors(containerColor=Lime)) {
                Text("DONE",color=Ink,fontWeight=FontWeight.Black)
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick=onUseCurrentLocation,modifier=Modifier.fillMaxWidth(),
            colors=ButtonDefaults.buttonColors(containerColor=Cyan)) {
            Text("USE CURRENT POSITION",color=Ink,fontWeight=FontWeight.Black)
        }
        if(locationStatus.isNotBlank()) Text(locationStatus,color=Cyan,fontSize=12.sp)
        Text("Tap to place the ring. Pan and zoom are enabled in this view.",color=Color.LightGray,fontSize=12.sp)
        OsmMap(selected=selected,radius=radius,onSelect=onSelect,gesturesEnabled=true,recenterToken=recenterToken,
            modifier=Modifier.fillMaxWidth().weight(1f))
        Column(Modifier.fillMaxWidth().padding(horizontal=12.dp)) {
            Text("Zone radius: ${radius/1000.0} km",color=Acid,fontSize=18.sp,fontWeight=FontWeight.Black)
            Slider(value=radius.toFloat(),onValueChange=onRadiusChange,valueRange=50f..10000f)
        }
        Text("© OpenStreetMap contributors · OpenFreeMap",fontSize=10.sp,color=Color.LightGray)
    }
}

@Composable
private fun MixerSlider(title:String,level:Int,onChange:(Int)->Unit) {
    Row(verticalAlignment=Alignment.CenterVertically) {
        Text(title,modifier=Modifier.weight(1f),color=Cyan,fontWeight=FontWeight.Bold,fontSize=12.sp)
        Text("$level%",color=Acid)
    }
    Slider(value=level.toFloat(),onValueChange={onChange(it.roundToInt())},valueRange=0f..100f)
}
