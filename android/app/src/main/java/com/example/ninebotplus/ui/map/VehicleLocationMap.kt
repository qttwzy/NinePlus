package com.example.ninebotplus.ui.map

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import com.example.ninebotplus.ui.theme.TeslaGreen
import com.example.ninebotplus.util.CoordinateTransform

private const val TAG = "NinePlusMap"

/**
 * Creates an AMap MapView with Compose lifecycle.
 * Uses MapView (GLSurfaceView) for maximum EGLContext compatibility across emulators.
 *
 * [passThroughTouch] lets parent Compose clickable receive taps (map view
 * otherwise swallows them even when gestures are disabled).
 */
@Composable
private fun rememberAmapView(passThroughTouch: Boolean = false): MapView {
    val context = LocalContext.current.applicationContext
    val mapView = remember(passThroughTouch) {
        object : MapView(context) {
            override fun onTouchEvent(event: MotionEvent?): Boolean {
                return if (passThroughTouch) false else super.onTouchEvent(event)
            }

            override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
                return if (passThroughTouch) false else super.dispatchTouchEvent(ev)
            }
        }.apply {
            onCreate(null)
            onResume()
        }
    }
    DisposableEffect(mapView) {
        onDispose {
            mapView.onPause()
            mapView.onDestroy()
        }
    }
    return mapView
}

private fun AMap.applyChinaChrome(interactive: Boolean = true) {
    uiSettings.isZoomControlsEnabled = false
    uiSettings.isCompassEnabled = false
    uiSettings.isScaleControlsEnabled = false
    uiSettings.isMyLocationButtonEnabled = false
    setMinZoomLevel(MapProviderConfig.MIN_ZOOM.toFloat())
    setMaxZoomLevel(MapProviderConfig.MAX_ZOOM.toFloat())
    if (!interactive) {
        uiSettings.setAllGesturesEnabled(false)
    }
}

/** No-GL placeholder when AMap cannot run (emulator / missing EGL). */
@Composable
private fun MapGlFallback(modifier: Modifier, compact: Boolean, title: String) {
    android.util.Log.i(TAG, "map GL fallback (no AMap MapView): $title")
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "地图引擎不可用",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = if (compact) 10.sp else 12.sp,
                )
                Text(
                    if (compact) "请在真机查看" else "模拟器无 OpenGL 兼容环境，真机可显示高德地图 · $title",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = if (compact) 8.sp else 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * Vehicle location map (高德官方 3D 地图 SDK).
 * Coordinates are raw WGS-84; [MapProviderConfig.toMapCoordinate] converts to GCJ-02 once.
 */
@Composable
fun VehicleLocationMap(
    latitude: Double,
    longitude: Double,
    title: String,
    modifier: Modifier = Modifier,
    privacyEnabled: Boolean = false,
    compact: Boolean = false,
) {
    if (privacyEnabled) {
        Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("位置已隐藏", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    // AMap GL thread crashes the process on emulators (EGL createContext failed).
    // Never inflate AMap MapView there — render a static card instead.
    if (!AmapSupport.shouldRenderAmap()) {
        MapGlFallback(modifier = modifier, compact = compact, title = title)
        return
    }

    val gcj = remember(latitude, longitude) {
        MapProviderConfig.toMapCoordinate(latitude, longitude)
    }
    val mapView = rememberAmapView(passThroughTouch = compact)

    Box(modifier = modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
        ) { view ->
            val amap = view.map ?: return@AndroidView
            amap.applyChinaChrome(interactive = !compact)
            amap.clear()
            val target = LatLng(gcj.latitude, gcj.longitude)
            amap.addMarker(
                MarkerOptions()
                    .position(target)
                    .anchor(0.5f, 0.5f)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)),
            )
            amap.moveCamera(CameraUpdateFactory.newLatLngZoom(target, if (compact) 15f else 15.5f))
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(if (compact) 4.dp else 8.dp),
        ) {
            Text(
                "© 高德地图",
                fontSize = if (compact) 8.sp else 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (!compact) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
            ) {
                Text(
                    title,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * Ride track map: polyline + start/end markers + fit bounds.
 * Input coordinates are raw WGS-84; transform happens here once (GCJ-02 for AMap).
 */
@Composable
fun RideTrackMap(
    points: List<CoordinateTransform.LatLng>,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) {
        Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("这条记录没有轨迹点", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    if (!AmapSupport.shouldRenderAmap()) {
        MapGlFallback(modifier = modifier, compact = false, title = "轨迹 ${points.size} 点")
        return
    }

    val gcjPoints = remember(points) {
        points.map { MapProviderConfig.toMapCoordinate(it.latitude, it.longitude) }
    }
    val mapView = rememberAmapView()

    Box(modifier = modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
        ) { view ->
            val amap = view.map ?: return@AndroidView
            amap.applyChinaChrome(interactive = true)
            amap.clear()

            val latLngs = gcjPoints.map { LatLng(it.latitude, it.longitude) }
            amap.addPolyline(
                PolylineOptions()
                    .addAll(latLngs)
                    .color(0xFF21D147.toInt())
                    .width(10f),
            )
            amap.addMarker(
                MarkerOptions()
                    .position(latLngs.first())
                    .title("开始")
                    .anchor(0.5f, 0.5f)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)),
            )
            amap.addMarker(
                MarkerOptions()
                    .position(latLngs.last())
                    .title("结束")
                    .anchor(0.5f, 0.5f)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)),
            )

            val bounds = LatLngBounds.Builder().apply { latLngs.forEach { include(it) } }.build()
            amap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80))
        }

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp),
        ) {
            Text(
                "© 高德地图",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        Column(
            Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
        ) {
            Text(
                "开始",
                fontSize = 10.sp,
                color = TeslaGreen,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp),
        ) {
            Text(
                "结束 · ${points.size} 点",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
