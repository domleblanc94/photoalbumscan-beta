package local.photoscan;

/** Coordinates are image fractions, in clockwise TL, TR, BR, BL order. */
final class CropGeometry {
    private CropGeometry() {}

    static boolean valid(float[] q) {
        if (q == null || q.length != 8) return false;
        for (float coordinate : q) {
            if (!Float.isFinite(coordinate) || coordinate < 0 || coordinate > 1) return false;
        }
        for (int i = 0; i < 8; i += 2) {
            int j = (i + 2) % 8;
            int k = (i + 4) % 8;
            double ax = q[j] - q[i];
            double ay = q[j + 1] - q[i + 1];
            double bx = q[k] - q[j];
            double by = q[k + 1] - q[j + 1];
            if (ax * by - ay * bx < 0.000001) return false;
        }
        return true;
    }

    static float[] canonical(float[] points) {
        float[] quad = signedArea(points) < 0
                ? new float[]{points[6], points[7], points[4], points[5], points[2], points[3], points[0], points[1]}
                : points;
        double[] dx = new double[4];
        double[] dy = new double[4];
        for (int i = 0; i < 4; i++) {
            int start = 2 * i;
            int end = (start + 2) % 8;
            double x = quad[end] - quad[start];
            double y = quad[end + 1] - quad[start + 1];
            double length = Math.max(1e-5, Math.hypot(x, y));
            dx[i] = x / length;
            dy[i] = y / length;
        }
        int start = 0;
        double best = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            double score = dx[i] + dy[(i + 1) % 4] - dx[(i + 2) % 4] - dy[(i + 3) % 4];
            if (score > best) {
                best = score;
                start = 2 * i;
            }
        }
        float[] result = new float[8];
        for (int i = 0; i < 8; i++) result[i] = quad[(i + start) % 8];
        return result;
    }

    static double area(float[] polygon) {
        return Math.abs(signedArea(polygon));
    }

    private static double signedArea(float[] polygon) {
        double sum = 0;
        for (int i = 0; i < polygon.length; i += 2) {
            int j = (i + 2) % polygon.length;
            sum += (double) polygon[i] * polygon[j + 1] - (double) polygon[j] * polygon[i + 1];
        }
        return sum / 2;
    }

    static double iou(float[] first, float[] second) {
        float[] polygon = first;
        for (int edge = 0; edge < 8 && polygon.length != 0; edge += 2) {
            int nextEdge = (edge + 2) % 8;
            float[] next = new float[polygon.length + 4];
            int count = 0;
            float px = polygon[polygon.length - 2];
            float py = polygon[polygon.length - 1];
            double prev = side(second, edge, nextEdge, px, py);
            for (int i = 0; i < polygon.length; i += 2) {
                float x = polygon[i];
                float y = polygon[i + 1];
                double current = side(second, edge, nextEdge, x, y);
                if ((current >= 0) != (prev >= 0)) {
                    double t = prev / (prev - current);
                    next[count++] = (float) (px + t * (x - px));
                    next[count++] = (float) (py + t * (y - py));
                }
                if (current >= 0) {
                    next[count++] = x;
                    next[count++] = y;
                }
                px = x;
                py = y;
                prev = current;
            }
            polygon = java.util.Arrays.copyOf(next, count);
        }
        double intersection = area(polygon);
        return intersection / Math.max(1e-8, area(first) + area(second) - intersection);
    }

    private static double side(float[] q, int a, int b, float x, float y) {
        return (double) (q[b] - q[a]) * (y - q[a + 1])
                - (double) (q[b + 1] - q[a + 1]) * (x - q[a]);
    }
    static float[] toRaw(float x, float y, int orientation) {
        return switch (orientation) {
            case 2 -> new float[]{1 - x, y};
            case 3 -> new float[]{1 - x, 1 - y};
            case 4 -> new float[]{x, 1 - y};
            case 5 -> new float[]{y, x};
            case 6 -> new float[]{y, 1 - x};
            case 7 -> new float[]{1 - y, 1 - x};
            case 8 -> new float[]{1 - y, x};
            default -> new float[]{x, y};
        };
    }

    static int sourceLength(float[] points, int a, int b, int c, int d) {
        double first = Math.hypot(points[a] - points[b], points[a + 1] - points[b + 1]);
        double second = Math.hypot(points[c] - points[d], points[c + 1] - points[d + 1]);
        return (int) Math.round(Math.max(first, second));
    }
}

