package com.example.ninebotplus.ui.map

import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
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
 * Host container: [MapView] + optional Android click overlay.
 *
 * MapView 是 SurfaceView，在模拟器/部分机型上会吞掉 Compose 覆盖层的点击。
 * compact 预览需要「点一下打开全屏」时，在 MapView 之上加一层真正的
 * Android View 承接点击。
 *
 * 生命周期：onCreate/onResume；离开时 onPause + 下一帧 onDestroy（避开 GL 竞态）。
 */
private class AmapViewContainer(context: android.content.Context) : FrameLayout(context) {
    val mapView: MapView = MapView(context)
    val clickOverlay: View = View(context)

    init {
        addView(mapView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(clickOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        clickOverlay.visibility = View.GONE
    }

    /** Show an Android view above MapView so taps open the parent action (SurfaceView eats Compose clicks). */
    fun setPreviewClickThrough(enabled: Boolean, onClick: (() -> Unit)?) {
        clickOverlay.visibility = if (enabled) View.VISIBLE else View.GONE
        clickOverlay.isClickable = enabled
        clickOverlay.isFocusable = enabled
        clickOverlay.setOnClickListener(
            if (enabled) {
                View.OnClickListener { onClick?.invoke() }
            } else {
                null
            },
        )
    }
}

/**
 * Creates an AMap map host with Compose lifecycle.
 */
@Composable
private fun rememberAmapView(
    passThroughTouch: Boolean = false,
    onSurfaceClick: (() -> Unit)? = null,
): AmapViewContainer {
    val context = LocalContext.current.applicationContext
    val container = remember { AmapViewContainer(context) }
    container.setPreviewClickThrough(passThroughTouch, onSurfaceClick)

    DisposableEffect(container) {
        val mapView = container.mapView
        try {
            com.amap.api.maps.MapsInitializer.updatePrivacyShow(context, true, true)
            com.amap.api.maps.MapsInitializer.updatePrivacyAgree(context, true)
            mapView.onCreate(null)
            mapView.onResume()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "amap MapView init failed", e)
        }
        onDispose {
            try {
                mapView.onPause()
            } catch (e: Exception) {
                android.util.Log.w(TAG, "amap MapView pause", e)
            }
            container.post {
                try {
                    mapView.onDestroy()
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "amap MapView destroy", e)
                }
            }
        }
    }
    return container
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
    onPreviewClick: (() -> Unit)? = null,
) {
    if (privacyEnabled) {
        Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("位置已隐藏", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    val gcj = remember(latitude, longitude) {
        MapProviderConfig.toMapCoordinate(latitude, longitude)
    }
    val mapHost = rememberAmapView(
        passThroughTouch = compact,
        onSurfaceClick = if (compact) onPreviewClick else null,
    )

    Box(modifier = modifier) {
        AndroidView(
            factory = { mapHost },
            modifier = Modifier.fillMaxSize(),
        ) { view ->
            val amap = view.mapView.map ?: return@AndroidView
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

    val gcjPoints = remember(points) {
        points.map { MapProviderConfig.toMapCoordinate(it.latitude, it.longitude) }
    }
    val mapHost = rememberAmapView(passThroughTouch = false)

    Box(modifier = modifier) {
        AndroidView(
            factory = { mapHost },
            modifier = Modifier.fillMaxSize(),
        ) { view ->
            val amap = view.mapView.map ?: return@AndroidView
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
