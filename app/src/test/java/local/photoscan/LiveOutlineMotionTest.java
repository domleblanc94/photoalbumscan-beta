package local.photoscan;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public final class LiveOutlineMotionTest {
    @Test public void smallJitterStaysStillAndSmallMovesSettleInNinetyMilliseconds() {
        LiveOutlineMotion motion = new LiveOutlineMotion();
        motion.update(List.of(rectangle(10, 10)), 100, 100, 1);
        motion.update(List.of(rectangle(11, 10)), 300, 300, 1);
        assertFalse(motion.isAnimating());
        assertArrayEquals(rectangle(10, 10), motion.sample(300).get(0), .001f);

        motion.update(List.of(rectangle(20, 10)), 500, 500, 1);
        assertTrue(motion.isAnimating());
        assertEquals(10f, motion.sample(500).get(0)[0], .001f);
        assertEquals(14.875f, motion.sample(545).get(0)[0], .001f);
        assertEquals(16.5f, motion.sample(590).get(0)[0], .001f);
        assertFalse(motion.isAnimating());
    }

    @Test public void detectorOrderingAndCyclicCornersKeepTheSameTracks() {
        LiveOutlineMotion motion = new LiveOutlineMotion();
        float[] left = rectangle(10, 10);
        float[] right = rectangle(150, 20);
        motion.update(List.of(left, right), 100, 100, 1);
        float[] rotatedOrder = {98, 10, 98, 90, 18, 90, 18, 10};
        motion.update(List.of(rectangle(154, 20), rotatedOrder), 300, 300, 1);
        assertArrayEquals(left, motion.sample(300).get(0), .001f);
        assertArrayEquals(right, motion.sample(300).get(1), .001f);
        assertArrayEquals(rectangle(15.2f, 10), motion.sample(390).get(0), .001f);
        assertArrayEquals(rectangle(152.6f, 20), motion.sample(390).get(1), .001f);
    }

    @Test public void aLostOutlineDisappearsAndLargeMovesSnapImmediately() {
        LiveOutlineMotion motion = new LiveOutlineMotion();
        motion.update(List.of(rectangle(10, 10), rectangle(150, 20)), 100, 100, 1);
        float[] newLocation = rectangle(250, 200);
        motion.update(List.of(newLocation), 300, 300, 1);
        assertEquals(1, motion.sample(300).size());
        assertArrayEquals(newLocation, motion.sample(300).get(0), .001f);
        assertFalse(motion.isAnimating());
        motion.update(List.of(), 500, 500, 1);
        assertTrue(motion.sample(500).isEmpty());
    }

    @Test public void anOldAnalysisCannotResurrectAnExpiredOutline() {
        LiveOutlineMotion motion = new LiveOutlineMotion();
        motion.update(List.of(rectangle(10, 10)), 420, 100, 1);
        assertEquals(750, motion.expiresAt());
        assertFalse(motion.expire(749));
        assertTrue(motion.expire(750));
        assertTrue(motion.sample(750).isEmpty());
        motion.update(List.of(rectangle(20, 10)), 850, 200, 1);
        assertTrue(motion.sample(850).isEmpty());
        assertFalse(motion.isAnimating());
    }

    @Test public void clearingBreaksTheTrackBeforeTheNextFrame() {
        LiveOutlineMotion motion = new LiveOutlineMotion();
        motion.update(List.of(rectangle(10, 10)), 100, 100, 1);
        motion.update(List.of(rectangle(16, 10)), 300, 300, 1);
        motion.clear();
        motion.update(List.of(rectangle(18, 10)), 310, 310, 1);
        assertArrayEquals(rectangle(18, 10), motion.sample(310).get(0), .001f);
        assertFalse(motion.isAnimating());
    }

    private static float[] rectangle(float x, float y) {
        return new float[]{x, y, x + 80, y, x + 80, y + 80, x, y + 80};
    }
}