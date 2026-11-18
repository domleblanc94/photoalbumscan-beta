package local.photoscan;

final class LevelMath {
    private static final double LEVEL_DEGREES = 2.5;

    private LevelMath() {}

    static float[] flatTilt(float x, float y, float z, int displayRotation) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) || z <= 0) return null;
        float right;
        float down;
        switch (displayRotation) {
            case 1: right = y; down = x; break;
            case 2: right = -x; down = y; break;
            case 3: right = -y; down = -x; break;
            default: right = x; down = -y; break;
        }
        return new float[]{(float) Math.toDegrees(Math.atan2(right, z)),
                (float) Math.toDegrees(Math.atan2(down, z))};
    }

    static boolean isLevel(float[] tilt) {
        return tilt != null && Math.hypot(tilt[0], tilt[1]) <= LEVEL_DEGREES;
    }
}