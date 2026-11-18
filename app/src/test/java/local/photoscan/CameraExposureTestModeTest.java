package local.photoscan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CameraExposureTestModeTest {
    @Test public void choosesNearestSupportedIndexAndReportsAppliedCompensation() {
        var setting = CameraExposureTestMode.select(-1, -2, 4, 1.0 / 3);
        assertEquals(-1, setting.requestedEv());
        assertEquals(-2, setting.index());
        assertEquals(-2.0 / 3, setting.appliedEv(), .00001);
        assertEquals(3, CameraExposureTestMode.select(1, -2, 4, 1.0 / 3).index());
        assertEquals(0, CameraExposureTestMode.select(0, -2, 4, 1.0 / 3).index());
    }

    @Test public void rejectsUnsupportedDirectionsAndInvalidSteps() {
        assertNull(CameraExposureTestMode.select(-1, 0, 4, .25));
        assertNull(CameraExposureTestMode.select(1, -3, 0, .25));
        assertNull(CameraExposureTestMode.select(1, -3, 3, Double.NaN));
        assertNull(CameraExposureTestMode.select(1, -3, 3, 0));
    }

    @Test public void timedOutExposureCannotFinishWhileResetIsPending() {
        var requests = new CameraExposureTestMode.RequestGate();
        int darker = requests.begin();
        assertTrue(requests.finish(darker)); // Timeout settles the first request locally.
        int normal = requests.begin();
        assertFalse(requests.finish(darker)); // A delayed camera callback cannot unblock the shutter.
        assertTrue(requests.pending());
        assertTrue(requests.finish(normal));
        assertFalse(requests.pending());
    }
}
