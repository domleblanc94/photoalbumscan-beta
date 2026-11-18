package local.photoscan;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LevelMathTest {
    @Test
    public void flatScreenIsCenteredAtEveryDisplayRotation() {
        for (int rotation = 0; rotation < 4; rotation++) {
            assertArrayEquals(new float[]{0, 0}, LevelMath.flatTilt(0, 0, 9.81f, rotation), .01f);
        }
    }

    @Test
    public void tiltFollowsTheDisplayAxesAtEveryRotation() {
        float a = (float) Math.toDegrees(Math.atan2(2, 9));
        float b = (float) Math.toDegrees(Math.atan2(1, 9));
        float[][] expected = {{a, b}, {-b, a}, {-a, -b}, {b, -a}};
        for (int rotation = 0; rotation < 4; rotation++) {
            assertArrayEquals(expected[rotation], LevelMath.flatTilt(2, -1, 9, rotation), .01f);
        }
    }

    @Test
    public void upsideDownAndInvalidSamplesHaveNoLevelReading() {
        assertNull(LevelMath.flatTilt(0, 0, -9.81f, 0));
        assertNull(LevelMath.flatTilt(0, 0, 0, 0));
        assertNull(LevelMath.flatTilt(Float.NaN, 0, 9.81f, 0));
    }

    @Test
    public void alignmentUsesCombinedTilt() {
        assertTrue(LevelMath.isLevel(new float[]{1, 2}));
        assertFalse(LevelMath.isLevel(new float[]{2, 2}));
        assertFalse(LevelMath.isLevel(null));
    }
}
