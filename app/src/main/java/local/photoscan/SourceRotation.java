package local.photoscan;

import android.graphics.Bitmap;

import java.io.File;
import java.io.IOException;

final class SourceRotation {
    private static final int FIRST_EDGE = 380;
    private static final int RETRY_EDGE = 640;

    private SourceRotation() {}

    // The beta has no face scorer; do not decode or allocate a crop for rotation.
    static int suggest(File original, float[] corners) { return -1; }

    static int suggest(Renderer renderer, Suggester suggester) {
        try {
            Bitmap initial = renderer.render(FIRST_EDGE, 0);
            try {
                int turns = suggester.suggest(initial);
                if (validTurns(turns)) return turns;
                if (Math.max(initial.getWidth(), initial.getHeight()) < FIRST_EDGE) return -1;
            } finally {
                initial.recycle();
            }

            if (Thread.currentThread().isInterrupted()) return -1;
            Bitmap retry = renderer.render(RETRY_EDGE, FIRST_EDGE);
            if (retry == null) return -1;
            try {
                int turns = suggester.suggest(retry);
                return validTurns(turns) ? turns : -1;
            } finally {
                retry.recycle();
            }
        } catch (IOException | RuntimeException unavailable) {
            // Keep the geometric crop usable if rotation scoring is unavailable.
            return -1;
        }
    }

    private static boolean validTurns(int turns) {
        return turns >= 0 && turns <= 3;
    }

    interface Renderer {
        Bitmap render(int maxEdge, int minNativeEdge) throws IOException;
    }

    interface Suggester {
        int suggest(Bitmap bitmap);
    }
}
