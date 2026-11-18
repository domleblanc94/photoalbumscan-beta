package local.photoscan;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Estimates whether a nearby line has stronger image support. Never supplies crop corners. */
final class CropReviewImageEvidence {
    private static final int MAX_EDGE = 1250;
    private static final int SAMPLES = 36;
    private static final int OFFSETS = 17;
    private static final int LINES = 1 + OFFSETS * 4;
    private static final double[] TILT_FRACTIONS = {0, .5, 1, 1.25};

    record Alternate(double gain, double displacement) {}

    private CropReviewImageEvidence() {}

    static Map<PictureContentDetector.Candidate, Alternate> analyze(
            PictureModelInput.Rgb detail, List<PictureContentDetector.Candidate> candidates) {
        if (detail == null || Thread.currentThread().isInterrupted()) return Map.of();
        boolean any = candidates.stream().anyMatch(CropReviewImageEvidence::eligible);
        if (!any) return Map.of();
        Mat input = new Mat();
        Mat scaled = new Mat();
        Mat lab = new Mat();
        try {
            input.create(detail.height(), detail.width(), CvType.CV_8UC3);
            input.put(0, 0, detail.pixels());
            double scale = Math.min(1., (double) MAX_EDGE / Math.max(detail.width(), detail.height()));
            int width = Math.max(1, (int) Math.round(detail.width() * scale));
            int height = Math.max(1, (int) Math.round(detail.height() * scale));
            Mat rgb = input;
            if (width != detail.width() || height != detail.height()) {
                Imgproc.resize(input, scaled, new Size(width, height), 0, 0, Imgproc.INTER_AREA);
                rgb = scaled;
            }
            Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab);
            Imgproc.GaussianBlur(lab, lab, new Size(3, 3), .75);
            lab.convertTo(lab, CvType.CV_32F);
            Map<PictureContentDetector.Candidate, Alternate> evidence = new IdentityHashMap<>();
            try (LineSampler sampler = new LineSampler()) {
                for (var candidate : candidates) {
                    if (Thread.currentThread().isInterrupted()) break;
                    if (!eligible(candidate)) continue;
                    Alternate strongest = sampler.strongest(lab, candidate.corners, width, height);
                    if (strongest != null) evidence.put(candidate, strongest);
                }
            }
            return evidence;
        } finally {
            input.release();
            scaled.release();
            lab.release();
        }
    }

    private static boolean eligible(PictureContentDetector.Candidate candidate) {
        return !candidate.omittedFromPhotos() && ImageEngine.validQuad(candidate.corners);
    }

    private static final class LineSampler implements AutoCloseable {
        private final float[] xMap = new float[LINES * SAMPLES];
        private final float[] yMap = new float[xMap.length];
        private final float[] outer = new float[xMap.length * 3];
        private final float[] inner = new float[outer.length];
        private final double[] displacement = new double[LINES];
        private final double[] peakDisplacement = new double[LINES];
        private final double[] weights = new double[SAMPLES];
        private final Mat mapX = new Mat();
        private final Mat mapY = new Mat();
        private final Mat result = new Mat();

        Alternate strongest(Mat lab, float[] quad, int width, int height) {
            double[] vx = new double[4], vy = new double[4], lengths = new double[4];
            for (int side = 0; side < 4; side++) {
                int a = side * 2, b = ((side + 1) % 4) * 2;
                vx[side] = ((double) quad[b] - quad[a]) * width;
                vy[side] = ((double) quad[b + 1] - quad[a + 1]) * height;
                lengths[side] = Math.hypot(vx[side], vy[side]);
                if (lengths[side] < 45) return null;
            }
            Alternate strongest = null;
            for (int side = 0; side < 4; side++) {
                int opposite = (side + 2) % 4;
                double dx = vx[side] / lengths[side], dy = vy[side] / lengths[side];
                double bx = -vx[opposite] / lengths[opposite], by = -vy[opposite] / lengths[opposite];
                double angle = Math.atan2(dx * by - dy * bx, dx * bx + dy * by);
                if (Math.abs(angle) > Math.toRadians(12)) continue;
                double radius = Math.min(lengths[side], Math.min(lengths[(side + 1) % 4],
                        lengths[(side + 3) % 4]));
                double x = (double) quad[side * 2] * width;
                double y = (double) quad[side * 2 + 1] * height;
                sample(lab, x, y, vx[side], vy[side], -dy, dx, angle, lengths[side], radius, -2, outer);
                sample(lab, x, y, vx[side], vy[side], -dy, dx, angle, lengths[side], radius, 2, inner);
                double baseline = score(0, false, radius);
                for (int line = 1; line < LINES; line++) {
                    double gain = score(line, true, radius) - baseline;
                    if (gain > 0 && (strongest == null || gain > strongest.gain())) {
                        strongest = new Alternate(gain, displacement[line] / radius);
                    }
                }
            }
            return strongest;
        }

        private void sample(Mat lab, double x, double y, double vx, double vy, double nx, double ny,
                            double angle, double length, double radius, int offset, float[] colors) {
            for (int line = 0; line < LINES; line++) {
                double center = line == 0 ? 0 : ((line - 1) % OFFSETS - 8) * .02 * radius;
                double slope = line == 0 ? 0 : Math.tan(angle * TILT_FRACTIONS[(line - 1) / OFFSETS]) * length;
                double displacementSum = 0, peak = 0;
                for (int point = 0; point < SAMPLES; point++) {
                    double t = .10 + .80 * point / (SAMPLES - 1);
                    double shift = center + slope * (t - .5);
                    double absShift = Math.abs(shift);
                    displacementSum += absShift;
                    peak = Math.max(peak, absShift);
                    int index = line * SAMPLES + point;
                    xMap[index] = (float) (x + t * vx + (shift + offset) * nx);
                    yMap[index] = (float) (y + t * vy + (shift + offset) * ny);
                }
                displacement[line] = displacementSum / SAMPLES;
                peakDisplacement[line] = peak;
            }
            mapX.create(LINES, SAMPLES, CvType.CV_32FC1);
            mapY.create(LINES, SAMPLES, CvType.CV_32FC1);
            mapX.put(0, 0, xMap);
            mapY.put(0, 0, yMap);
            Imgproc.remap(lab, result, mapX, mapY, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE);
            result.get(0, 0, colors);
        }

        private double score(int line, boolean requireDisplacement, double radius) {
            if (requireDisplacement && (displacement[line] < .045 * radius
                    || peakDisplacement[line] > .22 * radius)) return Double.NEGATIVE_INFINITY;
            int firstHalf = 0, secondHalf = 0, positive = 0, negative = 0;
            double sum = 0;
            for (int i = 0; i < SAMPLES; i++) {
                int index = (line * SAMPLES + i) * 3;
                double l = inner[index] - outer[index];
                double a = inner[index + 1] - outer[index + 1];
                double b = inner[index + 2] - outer[index + 2];
                double norm = Math.sqrt(l * l + a * a + b * b);
                if (norm >= 11) {
                    if (i < SAMPLES / 2) firstHalf++; else secondHalf++;
                }
                if (l >= 7) positive++;
                if (l <= -7) negative++;
                weights[i] = Math.min(norm, 40);
                sum += weights[i];
            }
            if (requireDisplacement && (Math.min(firstHalf, secondHalf) < .72 * (SAMPLES / 2)
                    || Math.max(positive, negative) < .72 * SAMPLES)) return Double.NEGATIVE_INFINITY;
            Arrays.sort(weights);
            // The linear 25th percentile of 36 samples falls 75% of the way from rank 8 to 9.
            return .25 * weights[8] + .75 * weights[9] + sum / SAMPLES;
        }

        @Override public void close() {
            mapX.release();
            mapY.release();
            result.release();
        }
    }
}
