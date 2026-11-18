package local.photoscan;

import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class OwnLocatorDecodeTest {
    private static final int GRID = 48;
    private static final int AREA = GRID * GRID;
    private static final QuadModel.Bounds LETTERBOX = new QuadModel.Bounds(0, 43, 384, 297);

    @Test public void preservesSixBaselineSlotsAndAppendsOnlyTwoNonOverlappingLowerPeaks() {
        Raw raw = new Raw();
        float[][] high = {rect(0, 0), rect(1, 0), rect(2, 0), rect(3, 0), rect(4, 0), rect(0, 1)};
        for (int i = high.length - 1; i >= 0; i--) raw.peak(2 + i * 3, 4, .6, high[i]);
        float[] first = rect(1, 1), second = rect(2, 1);
        raw.peak(1, 20, .27, high[0]); // Suppress against baseline.
        raw.peak(4, 20, .24, first);
        raw.peak(7, 20, .23, first); // Suppress against an already added slot.
        raw.peak(10, 20, .19, second);
        raw.peak(13, 20, .15, rect(3, 1)); // Limit the supplement to two.

        List<QuadModel.Prediction> baseline = raw.decode(false), trial = raw.decode(true);
        assertEquals(6, baseline.size());
        assertEquals(8, trial.size());
        for (int i = 0; i < baseline.size(); i++) {
            assertArrayEquals(high[i], baseline.get(i).quad(), 1e-6f);
            assertArrayEquals(baseline.get(i).quad(), trial.get(i).quad(), 0f);
            assertEquals(baseline.get(i).confidence(), trial.get(i).confidence(), 0f);
        }
        assertArrayEquals(first, trial.get(6).quad(), 1e-6f);
        assertArrayEquals(second, trial.get(7).quad(), 1e-6f);
        assertEquals(.24f, trial.get(6).confidence(), 1e-6f);
    }

    @Test public void doesNotRescueHighPeaksPastTop42OrIncludeLowPeaksBelowFloor() {
        Raw raw = new Raw();
        float[] invalid = {-.05f, .1f, .1f, .1f, .1f, .4f, -.05f, .4f};
        for (int i = 0; i < 42; i++) raw.peak(1 + i % 14 * 3, 1 + i / 14 * 3, .7, invalid);
        raw.peak(1, 16, .29, rect(0, 0));
        raw.peak(4, 16, .139, rect(1, 0));
        raw.peak(7, 16, .141, rect(2, 0));

        assertEquals(0, raw.decode(false).size());
        List<QuadModel.Prediction> trial = raw.decode(true);
        assertEquals(1, trial.size());
        assertArrayEquals(rect(2, 0), trial.get(0).quad(), 1e-6f);
    }

    private static float[] rect(int column, int row) {
        float x = .02f + column * .19f, y = .05f + row * .48f;
        return new float[]{x, y, x + .15f, y, x + .15f, y + .28f, x, y + .28f};
    }

    private static final class Raw {
        final float[] centers = new float[AREA];
        final float[] offsets = new float[AREA * 8];

        Raw() {
            Arrays.fill(centers, -20);
        }

        void peak(int x, int y, double confidence, float[] edges) {
            int cell = y * GRID + x;
            centers[cell] = (float) Math.log(confidence / (1 - confidence));
            for (int corner = 0; corner < 4; corner++) {
                float canvasX = edges[corner * 2] * LETTERBOX.width() + LETTERBOX.left();
                float canvasY = edges[corner * 2 + 1] * LETTERBOX.height() + LETTERBOX.top();
                offsets[corner * 2 * AREA + cell] = (canvasX - (x + .5f) * 8) / 384;
                offsets[(corner * 2 + 1) * AREA + cell] = (canvasY - (y + .5f) * 8) / 384;
            }
        }

        List<QuadModel.Prediction> decode(boolean supplement) {
            return QuadModel.decode(FloatBuffer.wrap(centers), FloatBuffer.wrap(offsets), LETTERBOX, supplement);
        }
    }
}
