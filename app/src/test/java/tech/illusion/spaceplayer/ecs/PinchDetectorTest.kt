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

    @Test
    fun `a realistic frame sequence stays pinched through the hysteresis band and does not re-engage after release`() {
        // Engage.
        val engage = PinchDetector.update(distanceMeters = 0.010f, wasPinching = false)
        assertTrue(engage.isPinching)
        assertTrue(engage.justEngaged)

        // Hover in the hysteresis band across a few frames - must stay pinched, no re-trigger.
        val hover1 = PinchDetector.update(distanceMeters = 0.030f, wasPinching = engage.isPinching)
        assertTrue(hover1.isPinching)
        assertFalse(hover1.justEngaged)

        val hover2 = PinchDetector.update(distanceMeters = 0.038f, wasPinching = hover1.isPinching)
        assertTrue(hover2.isPinching)
        assertFalse(hover2.justEngaged)

        val hover3 = PinchDetector.update(distanceMeters = 0.032f, wasPinching = hover2.isPinching)
        assertTrue(hover3.isPinching)
        assertFalse(hover3.justEngaged)

        // Release.
        val release = PinchDetector.update(distanceMeters = 0.060f, wasPinching = hover3.isPinching)
        assertFalse(release.isPinching)

        // Return to the band after release - must NOT re-engage inside the band.
        val afterRelease = PinchDetector.update(distanceMeters = 0.030f, wasPinching = release.isPinching)
        assertFalse(afterRelease.isPinching)
        assertFalse(afterRelease.justEngaged)
    }
}
