package com.geoklaxon.app

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*

private const val MAX_LAT=85.0
private fun my(lat:Double):Double {
    val r=Math.toRadians(lat.coerceIn(-MAX_LAT,MAX_LAT))
    return (1.0-ln(tan(Math.PI/4+r/2))/Math.PI)/2.0
}
private fun latFromY(y:Double):Double = Math.toDegrees(2*atan(exp(Math.PI*(1-2*y)))-Math.PI/2).coerceIn(-MAX_LAT,MAX_LAT)
private fun x(lon:Double)=(lon+180.0)/360.0
private fun lonFromX(px:Double)=((px*360-180+540)%360)-180

private typealias Ring=List<LatLon>
private data class Country(val polygons:List<Ring>)
private fun readRing(a:JSONArray):Ring=(0 until a.length()).mapNotNull { i ->
    val pair=a.optJSONArray(i) ?: return@mapNotNull null
    LatLon(pair.optDouble(1),pair.optDouble(0))
}
private fun readWorld(c: Context):List<Country> {
    val root=JSONObject(c.assets.open("world_countries.geojson").bufferedReader().use { it.readText() })
    val all=root.getJSONArray("features")
    return (0 until all.length()).map { idx ->
        val geo=all.getJSONObject(idx).getJSONObject("geometry")
        val coords=geo.getJSONArray("coordinates")
        val rings=mutableListOf<Ring>()
        if(geo.getString("type")=="Polygon") {
            for(j in 0 until coords.length()) rings.add(readRing(coords.getJSONArray(j)))
        } else {
            for(j in 0 until coords.length()) {
                val poly=coords.getJSONArray(j)
                for(k in 0 until poly.length()) rings.add(readRing(poly.getJSONArray(k)))
            }
        }
        Country(rings)
    }
}

/** Offline, geometrically placed WGS84 map with bundled settlement labels. */
@Composable
fun OfflineMap(selected:LatLon, radius:Int, onSelect:(LatLon)->Unit, modifier:Modifier=Modifier) {
    val context=LocalContext.current
    var countries by remember { mutableStateOf(emptyList<Country>()) }
    var centre by remember { mutableStateOf(selected) }
    var zoom by remember { mutableFloatStateOf(22f) }
    var canvasWidth by remember { mutableFloatStateOf(1f) }
    var canvasHeight by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(Unit) {
        countries=withContext(Dispatchers.IO){runCatching{readWorld(context)}.getOrDefault(emptyList())}
    }
    LaunchedEffect(selected) { centre=selected }
    Canvas(modifier
        .clip(RoundedCornerShape(14.dp))
        .background(Color(0xFF091B38))
        .pointerInput(Unit) { detectTapGestures { pos ->
            val worldW=canvasWidth*zoom
            val lon=lonFromX(x(centre.lon)+(pos.x-canvasWidth/2)/worldW)
            val lat=latFromY(my(centre.lat)+(pos.y-canvasHeight/2)/worldW)
            onSelect(LatLon(lat,lon))
        } }
        .pointerInput(Unit) { detectTransformGestures { _, pan, scale, _ ->
            val worldW=canvasWidth*zoom
            centre=LatLon(latFromY(my(centre.lat)-pan.y/worldW),lonFromX(x(centre.lon)-pan.x/worldW))
            zoom=(zoom*scale).coerceIn(0.9f,2500f)
        } }
    ) {
        canvasWidth=size.width; canvasHeight=size.height
        val worldW=size.width*zoom
        val ox=size.width/2f-x(centre.lon)*worldW
        val oy=size.height/2f-my(centre.lat)*worldW
        fun point(p:LatLon):Offset=Offset((ox+x(p.lon)*worldW).toFloat(),(oy+my(p.lat)*worldW).toFloat())
        // Faint coordinate lines, spaced independently of the geographic data.
        val interval=when {zoom>100->1;zoom>20->5;else->30}
        for(lon in -180..180 step interval) {
            val xx=(ox+x(lon.toDouble())*worldW).toFloat()
            drawLine(Color(0xFF14345A),Offset(xx,0f),Offset(xx,size.height),1f)
        }
        for(lat in -80..80 step interval) {
            val yy=(oy+my(lat.toDouble())*worldW).toFloat()
            drawLine(Color(0xFF14345A),Offset(0f,yy),Offset(size.width,yy),1f)
        }
        countries.forEach { country ->
            country.polygons.forEach { ring ->
                if(ring.size>=3) {
                    val path=Path()
                    ring.forEachIndexed { idx,p -> val pt=point(p);if(idx==0)path.moveTo(pt.x,pt.y)else path.lineTo(pt.x,pt.y) }
                    path.close()
                    drawPath(path,Color(0xFF245A62))
                    drawPath(path,Color(0xFF15DBD4),style=Stroke(width=1f))
                }
            }
        }
        val fence=GeoMath.circle(selected.lat,selected.lon,radius.toDouble())
        val path=Path()
        fence.forEachIndexed { i,p -> val pt=point(p);if(i==0)path.moveTo(pt.x,pt.y)else path.lineTo(pt.x,pt.y) }
        path.close()
        drawPath(path,Color(0x33FF39BA))
        drawPath(path,Color(0xFFFF39BA),style=Stroke(width=3f))
        val pin=point(selected)
        drawCircle(Color(0xFFFFEB00),radius=10f,center=pin)
        drawCircle(Color(0xFF20072E),radius=4f,center=pin)
    }
}

data class UKPlaceResult(
    val label: String,
    val context: String,
    val point: LatLon
)

/** Network is used only for voluntary search. Maps, preset positions and geofences remain offline. */
object FreeUKLookup {
    suspend fun lookup(query:String):List<UKPlaceResult> = withContext(Dispatchers.IO) {
        val input=query.trim()
        if(input.length<2 || input.length>80) return@withContext emptyList()
        val postcode=input.any(Char::isDigit)
        val path=if(postcode) "postcodes/${Uri.encode(input.uppercase())}" else "places?q=${Uri.encode(input)}&limit=10"
        val conn=(URL("https://api.postcodes.io/$path").openConnection() as HttpURLConnection).apply {
            connectTimeout=7000;readTimeout=7000;requestMethod="GET"
        }
        try {
            if(conn.responseCode!=200) return@withContext emptyList()
            val json=JSONObject(conn.inputStream.bufferedReader().use{it.readText()})
            val raw=json.opt("result")
            val matches=when(raw) {
                is JSONObject->listOf(raw)
                is JSONArray->(0 until raw.length()).mapNotNull(raw::optJSONObject)
                else->emptyList()
            }
            matches.mapNotNull { o ->
                val lat=o.optDouble("latitude",Double.NaN)
                val lon=o.optDouble("longitude",Double.NaN)
                if(!lat.isFinite() || !lon.isFinite()) return@mapNotNull null
                val label=o.optString("postcode").ifBlank { o.optString("name",input) }
                val context=listOf("parish","district","county","region","country")
                    .map { key -> o.optString(key) }
                    .filter { it.isNotBlank() && !it.equals(label,ignoreCase=true) }
                    .distinct()
                    .joinToString(" • ")
                UKPlaceResult(label,context,LatLon(lat,lon))
            }
        } finally { conn.disconnect() }
    }
}
