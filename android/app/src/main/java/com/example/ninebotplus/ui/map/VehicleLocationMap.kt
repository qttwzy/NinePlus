package com.example.ninebotplus.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.example.ninebotplus.ui.theme.TeslaGreen
import com.example.ninebotplus.util.CoordinateTransform
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Vehicle location map (MapLibre, no annotation plugin required).
 * WGS-84 input is converted to GCJ-02 before display.
 */
@Composable
fun VehicleLocationMap(
    latitude: Double,
    longitude: Double,
    title: String,
    modifier: Modifier = Modifier,
    privacyEnabled: Boolean = false,
) {
    if (privacyEnabled) {
        Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("位置已隐藏", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    val context = LocalContext.current
    val gcj = remember(latitude, longitude) {
        MapProviderConfig.toMapCoordinate(latitude, longitude)
    }
    val mapView = remember { MapView(context).apply { onCreate(null) } }

    DisposableEffect(mapView) {
        mapView.onStart()
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
        ) { view ->
            view.getMapAsync { map ->
                map.setStyle(MapProviderConfig.DEFAULT_STYLE_URI) { style ->
                    // Real geo-bound marker via GeoJSON point source + circle layer.
                    val point = Point.fromLngLat(gcj.longitude, gcj.latitude)
                    style.addSource(GeoJsonSource("vehicle-point", Feature.fromGeometry(point)))
                    style.addLayer(
                        CircleLayer("vehicle-marker", "vehicle-point").withProperties(
                            PropertyFactory.circleColor("#21D147"),
                            PropertyFactory.circleRadius(10f),
                            PropertyFactory.circleStrokeWidth(2f),
                            PropertyFactory.circleStrokeColor("#FFFFFF"),
                        ),
                    )
                    map.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(gcj.latitude, gcj.longitude))
                        .zoom(15.5)
                        .build()
                }
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(8.dp),
        ) {
            Text(
                title,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * Recorded-ride track map: polyline via GeoJsonSource + fit bounds + labels.
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

    val context = LocalContext.current
    val gcjPoints = remember(points) {
        points.map { MapProviderConfig.toMapCoordinate(it.latitude, it.longitude) }
    }
    val mapView = remember { MapView(context).apply { onCreate(null) } }

    DisposableEffect(mapView) {
        mapView.onStart()
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
        ) { view ->
            view.getMapAsync { map ->
                map.setStyle(MapProviderConfig.DEFAULT_STYLE_URI) { style ->
                    val lineString = LineString.fromLngLats(
                        gcjPoints.map { Point.fromLngLat(it.longitude, it.latitude) },
                    )
                    style.addSource(GeoJsonSource("ride-track", FeatureCollection.fromFeature(Feature.fromGeometry(lineString))))
                    style.addLayer(
                        LineLayer("ride-track-layer", "ride-track").withProperties(
                            PropertyFactory.lineColor("#21D147"),
                            PropertyFactory.lineWidth(5f),
                        ),
                    )

                    // Real geo-bound start/end markers.
                    val startPoint = Point.fromLngLat(gcjPoints.first().longitude, gcjPoints.first().latitude)
                    val endPoint = Point.fromLngLat(gcjPoints.last().longitude, gcjPoints.last().latitude)
                    style.addSource(GeoJsonSource("ride-start", Feature.fromGeometry(startPoint)))
                    style.addSource(GeoJsonSource("ride-end", Feature.fromGeometry(endPoint)))
                    style.addLayer(
                        CircleLayer("ride-start-layer", "ride-start").withProperties(
                            PropertyFactory.circleColor("#21D147"),
                            PropertyFactory.circleRadius(8f),
                            PropertyFactory.circleStrokeWidth(2f),
                            PropertyFactory.circleStrokeColor("#FFFFFF"),
                        ),
                    )
                    style.addLayer(
                        CircleLayer("ride-end-layer", "ride-end").withProperties(
                            PropertyFactory.circleColor("#FF453A"),
                            PropertyFactory.circleRadius(8f),
                            PropertyFactory.circleStrokeWidth(2f),
                            PropertyFactory.circleStrokeColor("#FFFFFF"),
                        ),
                    )

                    val latLngs = gcjPoints.map { LatLng(it.latitude, it.longitude) }
                    val bounds = LatLngBounds.Builder().includes(latLngs).build()
                    map.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80), 500)
                }
            }
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
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
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
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
