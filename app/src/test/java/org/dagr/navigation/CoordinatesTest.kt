package org.dagr.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinatesTest {
    @Test
    fun formatsLatitudeLongitudeWithStableDecimalSeparator() {
        assertEquals(
            "48.85830, 2.29450",
            formatCoordinates(48.8583, 2.2945, CoordinateFormat.LATITUDE_LONGITUDE)
        )
    }

    @Test
    fun formatsKnownLocationAsMeterPrecisionMgrs() {
        assertTrue(
            formatCoordinates(48.8583, 2.2945, CoordinateFormat.MGRS)
                .matches(Regex("31UDQ\\d{10}"))
        )
    }
}
