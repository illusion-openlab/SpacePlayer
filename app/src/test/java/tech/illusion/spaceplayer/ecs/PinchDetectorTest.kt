package tech.illusion.spaceplayer.ecs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinchDetectorTest {

    @Test
    fun `distance well inside engage threshold from not-pinching enters pinch with rising edge`() {
        val result = PinchDetector.update(distanceMeters = 0.010f, wasPinching = false)
        assertTrue(result.isPinching)
        assertTrue(result.justEngaged)
    }

    @Test
    fun `distance well inside engage threshold while already pinching has no rising edge`() {
        val result = PinchDetector.update(distanceMeters = 0.010f, wasPinching = true)
        assertTrue(result.isPinching)
        assertFalse(result.justEngaged)
    }

    @Test
    fun `distance well outside release threshold releases the pinch`() {
        val result = PinchDetector.update(distanceMeters = 0.060f, wasPinching = true)
        assertFalse(result.isPinching)
        assertFalse(result.justEngaged)
    }

    @Test
    fun `distance well outside release threshold while already not pinching stays not pinching`() {
        val result = PinchDetector.update(distanceMeters = 0.060f, wasPinching = false)
        assertFalse(result.isPinching)
        assertFalse(result.justEngaged)
    }

    @Test
    fun `distance in the hysteresis band holds the previous not-pinching state`() {
        val result = PinchDetector.update(distanceMeters = 0.032f, wasPinching = false)
        assertFalse(result.isPinching)
        assertFalse(result.justEngaged)
    }

    @Test
    fun `distance in the hysteresis band holds the previous pinching state`() {
        val result = PinchDetector.update(distanceMeters = 0.032f, wasPinching = true)
        assertTrue(result.isPinching)
        assertFalse(result.justEngaged)
    }

    @Test
    fun `distance exactly at the engage threshold does not engage (strict less-than)`() {
        val result = PinchDetector.update(
            distanceMeters = PinchDetector.ENGAGE_DISTANCE_METERS,
            wasPinching = false,
        )
        assertFalse(result.isPinching)
    }

    @Test
    fun `distance exactly at the release threshold does not release (strict greater-than)`() {
        val result = PinchDetector.update(
            distanceMeters = PinchDetector.RELEASE_DISTANCE_METERS,
            wasPinching = true,
        )
        assertTrue(result.isPinching)
    }

    @Test
    fun `two consecutive frames inside engage distance only report one rising edge`() {
        val first = PinchDetector.update(distanceMeters = 0.010f, wasPinching = false)
        val second = PinchDetector.update(distanceMeters = 0.010f, wasPinching = first.isPinching)
        assertTrue(first.justEngaged)
        assertFalse(second.justEngaged)
    }

    @Test
    fun `thresholds are ordered engage below release`() {
        assertTrue(PinchDetector.ENGAGE_DISTANCE_METERS < PinchDetector.RELEASE_DISTANCE_METERS)
    }
}
