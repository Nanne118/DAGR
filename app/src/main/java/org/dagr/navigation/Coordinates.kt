package org.dagr.navigation

import mil.nga.mgrs.MGRS
import java.util.Locale

enum class CoordinateFormat {
    LATITUDE_LONGITUDE,
    MGRS
}

fun formatCoordinates(latitude: Double, longitude: Double, format: CoordinateFormat): String =
    when (format) {
        CoordinateFormat.LATITUDE_LONGITUDE ->
            String.format(Locale.US, "%.5f, %.5f", latitude, longitude)
        CoordinateFormat.MGRS ->
            try {
                MGRS.from(longitude, latitude).coordinate(5)
            } catch (_: IllegalArgumentException) {
                "MGRS unavailable at this latitude"
            }
    }
