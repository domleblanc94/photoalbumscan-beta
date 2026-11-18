package local.photoscan;

import android.graphics.Bitmap;
import android.graphics.Matrix;

import java.util.Arrays;

/** Conservative cue for a clipped spot with a bright halo inside a proposed print. */
final class GlareAnalyzer {
    private static final int GRID = 32;
    private static final float[] SQUARE = {0, 0, 1, 0, 1, 1, 0, 1};
    private static final byte PALE = 1, HALO = 2, WHITE_CLIP = 3, TINTED_CLIP = 4, DUAL_CLIP = 5;
    private final float[] imageQuad = new float[8];
    private final float[] points = new float[GRID * GRID * 2];
    private final byte[] tones = new byte[GRID * GRID];
    private final boolean[] dark = new boolean[GRID * GRID];
    private final boolean[] visited = new boolean[GRID * GRID];
    private final int[] component = new int[GRID * GRID];
    private final Matrix projection = new Matrix();

    /** Returns a spot center in normalized frame coordinates, or null when evidence is uncertain. */
    float[] findSpot(Bitmap frame, float[] quad) {
        if (!CropGeometry.valid(quad) || CropGeometry.area(quad) < .025) return null;
        int width = frame.getWidth(), height = frame.getHeight();
        for (int i = 0; i < 8; i += 2) {
            imageQuad[i] = quad[i] * (width - 1);
            imageQuad[i + 1] = quad[i + 1] * (height - 1);
        }
        if (!projection.setPolyToPoly(SQUARE, 0, imageQuad, 0, 4)) return null;
        for (int y = 0; y < GRID; y++) {
            for (int x = 0; x < GRID; x++) {
                int i = 2 * (y * GRID + x);
                // Exclude the outer 12% of every edge, where sleeves and album borders can appear.
                points[i] = .12f + .76f * (x + .5f) / GRID;
                points[i + 1] = .12f + .76f * (y + .5f) / GRID;
            }
        }
        projection.mapPoints(points);
        int whites = 0, tinted = 0;
        for (int i = 0; i < tones.length; i++) {
            float mappedX = points[2 * i], mappedY = points[2 * i + 1];
            if (!Float.isFinite(mappedX) || !Float.isFinite(mappedY)) return null;
            int x = Math.round(mappedX), y = Math.round(mappedY);
            if (x < 0 || x >= width || y < 0 || y >= height) return null;
            int pixel = frame.getPixel(x, y);
            int r = (pixel >>> 16) & 255, g = (pixel >>> 8) & 255, b = pixel & 255;
            int low = Math.min(r, Math.min(g, b)), high = Math.max(r, Math.max(g, b));
            int luma = (54 * r + 183 * g + 19 * b) >> 8;
            dark[i] = luma <= 195;
            if (low >= 248) {
                tones[i] = WHITE_CLIP;
                whites++;
            } else if (high >= 248 && low >= 160 && high - low >= 16 && luma >= 197) {
                int middle = r + g + b - low - high;
                tones[i] = middle >= 242 ? DUAL_CLIP : TINTED_CLIP;
                tinted++;
            } else if (low >= 202 && high - low <= 25) {
                tones[i] = HALO;
            } else {
                tones[i] = low >= 155 && luma >= 183 ? PALE : 0;
            }
        }
        if (whites + tinted > tones.length / 8 || (whites < 6 && tinted < 12)) return null;
        Arrays.fill(visited, false);
        for (int i = 0; i < tones.length; i++) {
            if (visited[i] || tones[i] < WHITE_CLIP) continue;
            int center = inspectComponent(i, tones[i] != WHITE_CLIP);
            if (center >= 0) {
                return new float[]{points[2 * center] / width, points[2 * center + 1] / height};
            }
        }
        return null;
    }

    private int inspectComponent(int first, boolean tinted) {
        int left = GRID, top = GRID, right = 0, bottom = 0;
        int dual = 0;
        int size = enqueue(first, 0, tinted);
        for (int cursor = 0; cursor < size; cursor++) {
            int index = component[cursor];
            if (tones[index] == DUAL_CLIP) dual++;
            int x = index % GRID, y = index / GRID;
            left = Math.min(left, x);
            right = Math.max(right, x);
            top = Math.min(top, y);
            bottom = Math.max(bottom, y);
            if (x > 0) size = enqueue(index - 1, size, tinted);
            if (x + 1 < GRID) size = enqueue(index + 1, size, tinted);
            if (y > 0) size = enqueue(index - GRID, size, tinted);
            if (y + 1 < GRID) size = enqueue(index + GRID, size, tinted);
        }
        int width = right - left + 1, height = bottom - top + 1;
        if (size < 6 || size > 90 || size * 2 < width * height
                || width > 3 * height || height > 3 * width) return -1;
        if (tinted) {
            // A clipped color channel alone can be ordinary vivid content. Require two
            // near-clipped channels in part of a larger bright patch.
            if (size < 12 || dual < 4 || dual * 8 < size) return -1;
        } else if (left < 4 || top < 4 || right >= GRID - 4 || bottom >= GRID - 4) {
            return -1;
        }

        int nearWidth = tinted ? 3 : 2;
        int farWidth = tinted ? 6 : 4;
        int near = 0, halo = 0, far = 0, darker = 0;
        for (int y = Math.max(0, top - farWidth); y <= Math.min(GRID - 1, bottom + farWidth); y++) {
            for (int x = Math.max(0, left - farWidth); x <= Math.min(GRID - 1, right + farWidth); x++) {
                int distance = Math.max(Math.max(left - x, x - right), Math.max(top - y, y - bottom));
                int index = y * GRID + x;
                byte tone = tones[index];
                if (distance > 0 && distance <= nearWidth) {
                    near++;
                    if (tinted ? tone >= PALE : tone == HALO) halo++;
                } else if (distance >= 4) {
                    far++;
                    if (dark[index]) darker++;
                }
            }
        }
        // Require falloff. The tinted branch can use the surviving interior side of a broad patch.
        if (near < 12 || far < 12 || darker * 2 < far || halo * (tinted ? 12 : 2) < near) return -1;
        return ((top + bottom) / 2) * GRID + (left + right) / 2;
    }

    private int enqueue(int index, int size, boolean tinted) {
        if (visited[index] || (tinted ? tones[index] < TINTED_CLIP : tones[index] != WHITE_CLIP)) return size;
        visited[index] = true;
        component[size] = index;
        return size + 1;
    }
}
