package com.example.ninebotplus.util

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS-84 → GCJ-02 (Mars coordinates) transform.
 * Port of NinebotCoordinateTransform.swift. Vehicle GPS from Ninebot is WGS-84;
 * China map tiles (AMap / MapLibre China style) expect GCJ-02.
 */
object CoordinateTransform {
    data class LatLng(val latitude: Double, val longitude: Double)

    private const val EARTH_RADIUS = 6378245.0
    private const val EARTH_ECCENTRICITY = 0.00669342162296594323

    fun isInsideMainlandChina(latitude: Double, longitude: Double): Boolean =
        longitude > 72.004 && longitude < 137.8347 && latitude > 0.8293 && latitude < 55.8271

    fun gcj02(latitude: Double, longitude: Double): LatLng {
        if (!isInsideMainlandChina(latitude, longitude)) {
            return LatLng(latitude, longitude)
        }
        var latitudeDelta = transformLatitude(longitude - 105.0, latitude - 35.0)
        var longitudeDelta = transformLongitude(longitude - 105.0, latitude - 35.0)
        val radianLatitude = latitude / 180.0 * Math.PI
        var magic = sin(radianLatitude)
        magic = 1 - EARTH_ECCENTRICITY * magic * magic
        val sqrtMagic = sqrt(magic)
        latitudeDelta = (latitudeDelta * 180.0) / ((EARTH_RADIUS * (1 - EARTH_ECCENTRICITY)) / (magic * sqrtMagic) * Math.PI)
        longitudeDelta = (longitudeDelta * 180.0) / (EARTH_RADIUS / sqrtMagic * cos(radianLatitude) * Math.PI)
        return LatLng(latitude + latitudeDelta, longitude + longitudeDelta)
    }

    fun gcj02ToWgs84(latitude: Double, longitude: Double): LatLng {
        if (!isInsideMainlandChina(latitude, longitude)) {
            return LatLng(latitude, longitude)
        }
        val gcj = gcj02(latitude, longitude)
        val dLat = gcj.latitude - latitude
        val dLng = gcj.longitude - longitude
        return LatLng(latitude - dLat, longitude - dLng)
    }

    /** Haversine distance in meters. */
    fun distanceMeters(a: LatLng, b: LatLng): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS * atan2(sqrt(h), sqrt(1 - h))
    }

    fun bearingDegrees(a: LatLng, b: LatLng): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun transformLatitude(x: Double, y: Double): Double {
        var result = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        result += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        result += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        result += (160.0 * sin(y / 12.0 * Math.PI) + 320.0 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return result
    }

    private fun transformLongitude(x: Double, y: Double): Double {
        var result = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        result += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        result += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        result += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return result
    }
}
