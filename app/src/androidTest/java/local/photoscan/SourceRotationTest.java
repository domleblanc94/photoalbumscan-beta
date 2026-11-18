package local.photoscan;

import android.graphics.Bitmap;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class SourceRotationTest {
    @Test public void preservesAcceptedTurnsWithoutRetry() {
        for (int accepted = 0; accepted < 4; accepted++) {
            final int turns = accepted;
            Bitmap crop = bitmap(380);
            int result = SourceRotation.suggest((edge, minimum) -> {
                assertEquals(380, edge);
                assertEquals(0, minimum);
                return crop;
            }, ignored -> turns);
            assertEquals(turns, result);
            assertTrue(crop.isRecycled());
        }
    }

    @Test public void abstainsWithoutRetryBelowFirstEdge() {
        Bitmap crop = bitmap(379);
        int result = SourceRotation.suggest((edge, minimum) -> {
            assertEquals(380, edge);
            return crop;
        }, ignored -> -1);
        assertEquals(-1, result);
        assertTrue(crop.isRecycled());
    }

    @Test public void doesNotRenderRetryAtTheSameNativeResolution() throws Exception {
        File directory = InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
        File source = File.createTempFile("source-rotation-", ".png", directory);
        Bitmap crop = bitmap(380);
        try {
            try (FileOutputStream output = new FileOutputStream(source)) {
                assertTrue(crop.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
            float[] whole = {0, 0, 1, 0, 1, 1, 0, 1};
            assertNull(ImageEngine.renderRotationInput(source, whole, 640, 380));
        } finally {
            crop.recycle();
            source.delete();
        }
    }

    @Test public void retriesAnAbstentionAndRecyclesBothCrops() {
        Bitmap first = bitmap(380);
        Bitmap retry = bitmap(620);
        int result = SourceRotation.suggest((edge, minimum) -> {
            if (edge == 380) return first;
            assertEquals(640, edge);
            assertEquals(380, minimum);
            assertTrue(first.isRecycled());
            return retry;
        }, crop -> crop == first ? -1 : 3);
        assertEquals(3, result);
        assertTrue(retry.isRecycled());
    }

    @Test public void failedRetryRenderReturnsNoSuggestion() {
        Bitmap first = bitmap(380);
        int result = SourceRotation.suggest((edge, minimum) -> {
            if (edge == 380) return first;
            throw new IOException("Source unavailable");
        }, ignored -> -1);
        assertEquals(-1, result);
        assertTrue(first.isRecycled());
    }

    @Test public void scoringFailureRecyclesTheRetry() {
        Bitmap first = bitmap(380);
        Bitmap retry = bitmap(640);
        int result = SourceRotation.suggest((edge, minimum) -> edge == 380 ? first : retry, crop -> {
            if (crop == first) return -1;
            throw new IllegalStateException("Scorer unavailable");
        });
        assertEquals(-1, result);
        assertTrue(first.isRecycled());
        assertTrue(retry.isRecycled());
    }

    @Test public void renderFailureSkipsScoring() {
        int result = SourceRotation.suggest((edge, minimum) -> {
            assertEquals(380, edge);
            throw new IOException("Source unavailable");
        }, ignored -> {
            fail("Scoring requires a rendered crop");
            return -1;
        });
        assertEquals(-1, result);
    }

    private static Bitmap bitmap(int width) {
        return Bitmap.createBitmap(width, 160, Bitmap.Config.ARGB_8888);
    }
}
