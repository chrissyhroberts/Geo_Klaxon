package com.geoklaxon.app

import org.junit.Assert.*
import org.junit.Test

class GeoMathTest {
    @Test fun circleIsGeodesicAtUKLatitude() {
        val center=LatLon(53.9583,-1.0803)
        val points=GeoMath.circle(center.lat,center.lon,2000.0,72)
        assertEquals(72,points.size)
        points.forEach { assertEquals(2000.0,GeoMath.metresBetween(center,it),1.0) }
    }
    @Test fun datelineWrapIsValid() {
        val points=GeoMath.circle(0.0,179.99,5000.0)
        assertTrue(points.all { it.lon in -180.0..180.0 })
    }
}
