package local.photoscan;

/** Keeps an isolated or stale live observation from flashing a warning. */
final class GlareHint {
    static final long SAMPLE_INTERVAL_MS = 400;
    private static final long MAX_SAMPLE_GAP_MS = 1000;
    private float[] lastSpot;
    private long lastSample;
    private int misses;
    private boolean visible;

    boolean observeVisible(boolean enabled, float[][] spots, int visibleOutlines, long at) {
        float[] chosen = null;
        float best = Float.POSITIVE_INFINITY;
        if (enabled && spots != null) {
            for (int i = 0; i < spots.length; i++) {
                float[] spot = spots[i];
                if ((visibleOutlines & (1 << i)) == 0 || spot == null) continue;
                if (chosen == null) chosen = spot;
                if (lastSpot == null || at - lastSample > MAX_SAMPLE_GAP_MS) continue;
                float distance = distanceSquared(spot, lastSpot);
                if (distance <= .065f * .065f && distance < best) {
                    chosen = spot;
                    best = distance;
                }
            }
        }
        return observe(enabled, chosen, at);
    }

    boolean observe(boolean enabled, float[] spot, long at) {
        if (!enabled) {
            clear();
            return false;
        }
        if (at - lastSample > MAX_SAMPLE_GAP_MS) clear();
        if (spot != null && lastSpot != null && distanceSquared(spot, lastSpot) <= .065f * .065f) {
            visible = true;
            misses = 0;
        } else if (++misses >= 2) {
            visible = false;
        }
        lastSpot = spot;
        lastSample = at;
        return visible;
    }

    boolean visible() { return visible; }

    void clear() {
        lastSpot = null;
        lastSample = 0;
        misses = 0;
        visible = false;
    }

    private static float distanceSquared(float[] first, float[] second) {
        float dx = first[0] - second[0], dy = first[1] - second[1];
        return dx * dx + dy * dy;
    }
}
