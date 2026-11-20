package com.geoklaxon.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap.OnMapClickListener
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

private const val OPEN_FREE_MAP_STYLE="https://tiles.openfreemap.org/styles/liberty"
private const val FENCE_SOURCE="geoklaxon-fence"
private const val FENCE_FILL="geoklaxon-fence-fill"
private const val FENCE_LINE="geoklaxon-fence-line"

/** OSM-derived vector map rendered by MapLibre. The style has no API-key requirement. */
@Composable
fun OsmMap(selected:LatLon, radius:Int, onSelect:(LatLon)->Unit, gesturesEnabled:Boolean, recenterToken:Int=0, modifier:Modifier=Modifier) {
    val context=LocalContext.current
    val lifecycleOwner=LocalLifecycleOwner.current
    val latestOnSelect=rememberUpdatedState(onSelect)
    val mapView=remember {
        MapLibre.getInstance(context)
        MapView(context).also { it.onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var style by remember { mutableStateOf<Style?>(null) }
    var marker by remember { mutableStateOf<Marker?>(null) }
    var cameraPlaced by remember { mutableStateOf(false) }
    var appliedRecenterToken by remember { mutableIntStateOf(0) }

    DisposableEffect(mapView,lifecycleOwner) {
        val observer=LifecycleEventObserver { _,event ->
            when(event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    AndroidView(
        modifier=modifier.clip(RoundedCornerShape(14.dp)),
        factory={
            mapView.apply {
                getMapAsync { loaded:MapLibreMap ->
                    map=loaded
                    loaded.uiSettings.isRotateGesturesEnabled=false
                    loaded.uiSettings.isTiltGesturesEnabled=false
                    loaded.uiSettings.isScrollGesturesEnabled=false
                    loaded.uiSettings.isZoomGesturesEnabled=false
                    loaded.addOnMapClickListener(object: OnMapClickListener {
                        override fun onMapClick(point: LatLng): Boolean {
                            latestOnSelect.value(LatLon(point.latitude,point.longitude))
                            return true
                        }
                    })
                    loaded.setStyle(Style.Builder().fromUri(OPEN_FREE_MAP_STYLE)) { loadedStyle:Style ->
                        style=loadedStyle
                        loadedStyle.addSource(GeoJsonSource(FENCE_SOURCE))
                        loadedStyle.addLayer(FillLayer(FENCE_FILL,FENCE_SOURCE).withProperties(
                            fillColor("#FF39B9"),fillOpacity(0.20f)))
                        loadedStyle.addLayer(LineLayer(FENCE_LINE,FENCE_SOURCE).withProperties(
                            lineColor("#FF39B9"),lineWidth(3f)))
                        styleReady=true
                    }
                }
            }
        },
        update={
            map?.uiSettings?.isScrollGesturesEnabled=gesturesEnabled
            map?.uiSettings?.isZoomGesturesEnabled=gesturesEnabled
        }
    )

    LaunchedEffect(map,style,styleReady,selected,radius,recenterToken) {
        val loaded=map ?: return@LaunchedEffect
        val loadedStyle=style ?: return@LaunchedEffect
        if(!styleReady) return@LaunchedEffect
        val target=LatLng(selected.lat,selected.lon)
        marker?.let { loaded.removeMarker(it) }
        marker=loaded.addMarker(MarkerOptions().position(target).title("Destination"))
        val ring: List<Point> = GeoMath.circle(selected.lat,selected.lon,radius.toDouble())
            .map { point -> Point.fromLngLat(point.lon,point.lat) }
            .let { points -> points + points.first() }
        loadedStyle.getSourceAs<GeoJsonSource>(FENCE_SOURCE)?.setGeoJson(
            Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))
        )
        if(!cameraPlaced || appliedRecenterToken!=recenterToken) {
            loaded.cameraPosition=CameraPosition.Builder().target(target).zoom(11.0).build()
            cameraPlaced=true
            appliedRecenterToken=recenterToken
        }
    }
}
