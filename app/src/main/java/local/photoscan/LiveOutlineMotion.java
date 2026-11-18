package local.photoscan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Short-lived interpolation for projected preview coordinates, owned by the UI thread. */
final class LiveOutlineMotion {
    static final long TRANSITION_MS = 90;
    static final long MAX_FRAME_AGE_MS = 650;
    private static final int MAX_OUTLINES = 6;
    private static final float FOLLOW = .65f;

    private static final class Track {
        final float[] observed;
        final float[] start;
        final float[] end;
        final float[] display;

        Track(float[] observed, float[] start, float[] end) {
            this.observed = observed.clone();
            this.start = start.clone();
            this.end = end.clone();
            display = start.clone();
        }
    }

    private final ArrayList<Track> tracks = new ArrayList<>(MAX_OUTLINES);
    private final ArrayList<float[]> displayed = new ArrayList<>(MAX_OUTLINES);
    private long startedAt;
    private long expiresAt;
    private boolean moving;

    void update(List<float[]> quads, long now, long measuredAt, float density) {
        if (quads.isEmpty() || now - measuredAt >= MAX_FRAME_AGE_MS) {
            clear();
            return;
        }
        expire(now);
        sample(now);
        int count = Math.min(quads.size(), MAX_OUTLINES);
        int[] matches = new int[tracks.size()];
        Arrays.fill(matches, -1);
        int[] starts = new int[tracks.size()];
        boolean[] assigned = new boolean[count];
        match(quads, density, matches, starts, assigned);

        ArrayList<Track> next = new ArrayList<>(count);
        moving = false;
        for (int i = 0; i < tracks.size(); i++) {
            if (matches[i] < 0) continue;
            float[] start = tracks.get(i).display;
            float[] observed = aligned(quads.get(matches[i]), starts[i]);
            float[] target = start.clone();
            float deadband = 1.25f * density;
            for (int k = 0; k < 8; k += 2) {
                if (squaredDistance(start, observed, k) <= deadband * deadband) continue;
                target[k] += (observed[k] - start[k]) * FOLLOW;
                target[k + 1] += (observed[k + 1] - start[k + 1]) * FOLLOW;
                moving = true;
            }
            next.add(new Track(observed, start, target));
        }
        for (int j = 0; j < count; j++) {
            if (!assigned[j]) {
                float[] quad = quads.get(j);
                next.add(new Track(quad, quad, quad));
            }
        }
        tracks.clear();
        tracks.addAll(next);
        displayed.clear();
        for (Track track : tracks) displayed.add(track.display);
        startedAt = now;
        expiresAt = measuredAt + MAX_FRAME_AGE_MS;
    }

    private void match(List<float[]> quads, float density, int[] matches, int[] starts, boolean[] assigned) {
        // Match the closest remaining pair first, at most six by six.
        float[][] costs = new float[matches.length][assigned.length];
        int[][] offsets = new int[matches.length][assigned.length];
        for (int i = 0; i < matches.length; i++) {
            float[] previous = tracks.get(i).observed;
            float limit = Math.max(7 * density, Math.min(42 * density, shortestEdge(previous) * .22f));
            for (int j = 0; j < assigned.length; j++) {
                float[] candidate = quads.get(j);
                float best = Float.POSITIVE_INFINITY;
                for (int start = 0; start < 8; start += 2) {
                    float maximum = 0;
                    for (int k = 0; k < 8; k += 2) {
                        int at = (k + start) % 8;
                        float x = previous[k] - candidate[at], y = previous[k + 1] - candidate[at + 1];
                        maximum = Math.max(maximum, x * x + y * y);
                    }
                    if (maximum < best) {
                        best = maximum;
                        offsets[i][j] = start;
                    }
                }
                costs[i][j] = best <= limit * limit ? best : Float.POSITIVE_INFINITY;
            }
        }
        for (int n = 0; n < Math.min(matches.length, assigned.length); n++) {
            int old = -1;
            int fresh = -1;
            float best = Float.POSITIVE_INFINITY;
            for (int i = 0; i < matches.length; i++) {
                if (matches[i] >= 0) continue;
                for (int j = 0; j < assigned.length; j++) {
                    if (!assigned[j] && costs[i][j] < best) {
                        old = i;
                        fresh = j;
                        best = costs[i][j];
                    }
                }
            }
            if (old < 0) break;
            matches[old] = fresh;
            starts[old] = offsets[old][fresh];
            assigned[fresh] = true;
        }
    }

    private static float[] aligned(float[] quad, int start) {
        if (start == 0) return quad;
        float[] result = new float[8];
        for (int i = 0; i < 8; i++) result[i] = quad[(i + start) % 8];
        return result;
    }

    private static float shortestEdge(float[] quad) {
        float shortest = Float.POSITIVE_INFINITY;
        for (int i = 0; i < 8; i += 2) {
            int j = (i + 2) % 8;
            float x = quad[i] - quad[j], y = quad[i + 1] - quad[j + 1];
            shortest = Math.min(shortest, x * x + y * y);
        }
        return (float) Math.sqrt(shortest);
    }

    private static float squaredDistance(float[] first, float[] second, int corner) {
        float x = first[corner] - second[corner], y = first[corner + 1] - second[corner + 1];
        return x * x + y * y;
    }

    List<float[]> sample(long now) {
        if (moving) {
            float fraction = Math.min(1, Math.max(0, (now - startedAt) / (float) TRANSITION_MS));
            float ease = fraction * (2 - fraction);
            for (Track track : tracks) {
                for (int k = 0; k < 8; k++) track.display[k] = track.start[k] + (track.end[k] - track.start[k]) * ease;
            }
            if (fraction == 1) moving = false;
        }
        return displayed;
    }

    boolean isAnimating() { return moving; }
    long expiresAt() { return expiresAt; }

    boolean expire(long now) {
        if (tracks.isEmpty() || now < expiresAt) return false;
        clear();
        return true;
    }

    void clear() {
        tracks.clear();
        displayed.clear();
        expiresAt = 0;
        moving = false;
    }
}
