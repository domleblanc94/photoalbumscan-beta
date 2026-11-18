package local.photoscan;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Collections;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/** Selects a physical print edge near each model side on a higher-resolution review image. */
final class BoundaryRefiner {
    private static final int CENTERS = 25;
    private static final int SLOPES = 17;
    private static final int CANDIDATES = CENTERS * SLOPES;
    private static final int SAMPLES = 43;
    private static final int FEATURES = 40;
    private static final float[] BANDS = {-23, -13, -7, -4, -2, 2, 4, 7, 13, 23};
    private static final int[][] CONTRAST_BANDS = {{4, 5}, {3, 6}, {2, 7}};
    private static final int[][] TEXTURE_BANDS = {{0, 2}, {7, 9}, {1, 3}, {6, 8}};
    private static final long[] INPUT = {4, CANDIDATES, FEATURES};
    private static final String ASSET = "edge-ranker.onnx";

    private final OrtEnvironment environment;
    // Keep options alive while the process-wide session is in use.
    private final OrtSession.SessionOptions options;
    private final OrtSession session;

    private BoundaryRefiner(byte[] model) throws OrtException {
        environment = OfflineOrt.environment();
        options = new OrtSession.SessionOptions();
        try {
            options.setInterOpNumThreads(1);
            options.setIntraOpNumThreads(1);
            options.addConfigEntry("session.intra_op.allow_spinning", "0");
            session = environment.createSession(model, options);
        } catch (OrtException | RuntimeException failure) {
            options.close();
            throw failure;
        }
    }

    static BoundaryRefiner load(Context context) throws IOException, OrtException {
        try (InputStream input = context.getAssets().open(ASSET);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] block = new byte[8192];
            for (int count; (count = input.read(block)) != -1;) output.write(block, 0, count);
            return new BoundaryRefiner(output.toByteArray());
        } catch (FileNotFoundException absent) {
            return null;
        }
    }

    List<float[]> refine(Mat rgba, List<float[]> proposals) throws OrtException {
        List<float[]> result = new ArrayList<>(proposals.size());
        try (Workspace workspace = new Workspace()) {
            for (float[] proposal : proposals) result.add(refineOne(rgba, proposal, workspace));
        }
        return result;
    }

    private float[] refineOne(Mat rgba, float[] proposal, Workspace work) throws OrtException {
        if (!CropGeometry.valid(proposal)) return proposal;
        int sourceWidth = rgba.cols();
        int sourceHeight = rgba.rows();
        float[] source = proposal.clone();
        for (int i = 0; i < 8; i += 2) {
            source[i] *= sourceWidth;
            source[i + 1] *= sourceHeight;
        }
        double wide = edgeLength(source, 0, 2, 6, 4);
        double high = edgeLength(source, 0, 6, 2, 4);
        if (Math.min(wide, high) < 30) return proposal;
        double scale = Math.min(1, 570 / Math.max(wide, high));
        int width = Math.max(30, (int) Math.round(wide * scale));
        int height = Math.max(30, (int) Math.round(high * scale));
        int span = Math.min(width, height);
        int margin = (int) Math.ceil(span * 0.23 + 25);
        int warpedWidth = width + 2 * margin + 1;
        int warpedHeight = height + 2 * margin + 1;
        MatOfPoint2f quad = new MatOfPoint2f(new Point(source[0], source[1]), new Point(source[2], source[3]),
                new Point(source[4], source[5]), new Point(source[6], source[7]));
        MatOfPoint2f target = new MatOfPoint2f(new Point(margin, margin), new Point(margin + width, margin),
                new Point(margin + width, margin + height), new Point(margin, margin + height));
        Mat transform = Imgproc.getPerspectiveTransform(quad, target);
        Mat warped = new Mat();
        Mat lab = new Mat();
        Mat side = new Mat();
        try {
            Imgproc.warpPerspective(rgba, warped, transform, new Size(warpedWidth, warpedHeight),
                    Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE);
            Imgproc.cvtColor(warped, lab, Imgproc.COLOR_RGBA2RGB);
            Imgproc.cvtColor(lab, lab, Imgproc.COLOR_RGB2Lab);
            Imgproc.GaussianBlur(lab, lab, new Size(3, 3), 0.65);
            lab.convertTo(lab, CvType.CV_32F);
            warped.release();
            float[] strips = new float[4 * CANDIDATES * FEATURES];
            describe(lab, margin, width, span, strips, 0, work);
            Core.transpose(lab, side);
            Core.flip(side, side, 0);
            describe(side, margin, height, span, strips, CANDIDATES * FEATURES, work);
            Core.flip(lab, side, 0);
            describe(side, margin, width, span, strips, 2 * CANDIDATES * FEATURES, work);
            Core.transpose(lab, side);
            describe(side, margin, height, span, strips, 3 * CANDIDATES * FEATURES, work);
            try (OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(strips), INPUT);
                 OrtSession.Result result = session.run(Collections.singletonMap("strips", input))) {
                FloatBuffer logits = ((OnnxTensor) result.get("logits").orElseThrow()).getFloatBuffer();
                return decode(logits, proposal, transform, width, height, margin, span, sourceWidth, sourceHeight);
            }
        } finally {
            side.release();
            lab.release();
            warped.release();
            transform.release();
            target.release();
            quad.release();
        }
    }

    private static final class Workspace implements AutoCloseable {
        final Mat xMap = new Mat(CANDIDATES * BANDS.length, SAMPLES, CvType.CV_32FC1);
        final Mat yMap = new Mat(CANDIDATES * BANDS.length, SAMPLES, CvType.CV_32FC1);
        final Mat sample = new Mat();
        final float[] x = new float[CANDIDATES * BANDS.length * SAMPLES];
        final float[] y = new float[x.length];
        final float[] pixels = new float[3 * x.length];
        final float[] sorted = new float[SAMPLES];

        @Override public void close() {
            xMap.release();
            yMap.release();
            sample.release();
        }
    }

    private static void describe(Mat lab, int margin, int length, int span, float[] output, int offset, Workspace work) {
        float bandScale = Math.max(0.68f, Math.min(1.23f, span / 365f));
        int mapIndex = 0;
        for (int candidate = 0; candidate < CANDIDATES; candidate++) {
            float center = center(candidate);
            float slope = slope(candidate);
            for (float band : BANDS) {
                for (int sample = 0; sample < SAMPLES; sample++) {
                    float t = 0.08f + 0.84f * sample / (SAMPLES - 1);
                    work.x[mapIndex] = margin + t * length;
                    work.y[mapIndex++] = margin + span * (center + slope * (t - 0.5f)) + band * bandScale;
                }
            }
        }
        work.xMap.put(0, 0, work.x);
        work.yMap.put(0, 0, work.y);
        Imgproc.remap(lab, work.sample, work.xMap, work.yMap, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE);
        work.sample.get(0, 0, work.pixels);
        for (int candidate = 0; candidate < CANDIDATES; candidate++) {
            int begin = offset + candidate * FEATURES;
            int out = begin;
            int base = candidate * BANDS.length * SAMPLES * 3;
            float center = center(candidate);
            float slope = slope(candidate);
            output[out++] = center * 10;
            output[out++] = Math.abs(center) * 10;
            output[out++] = slope * 10;
            output[out++] = Math.abs(slope) * 10;
            for (int[] bands : CONTRAST_BANDS) {
                out = contrastFeatures(work, base, bands[0], bands[1], output, out);
            }
            out = textureFeatures(work.pixels, base, 1, output, out);
            out = textureFeatures(work.pixels, base, 6, output, out);
            for (int[] bands : TEXTURE_BANDS) {
                double strength = 0;
                double luma = 0;
                for (int t = 0; t < SAMPLES; t++) {
                    int a = pixelIndex(base, bands[0], t);
                    int b = pixelIndex(base, bands[1], t);
                    strength += Math.min(45, distance(work.pixels, a, b));
                    luma += work.pixels[b] - work.pixels[a];
                }
                output[out++] = (float) (strength / (SAMPLES * 42));
                output[out++] = (float) (luma / (SAMPLES * 42));
            }
            output[out++] = length / 570f;
            output[out++] = span / 570f;
            if (out != begin + FEATURES) throw new IllegalStateException("Edge feature length changed");
        }
    }

    private static int contrastFeatures(Workspace work, int base, int low, int high, float[] output, int out) {
        double sum = 0;
        double luma = 0;
        int support = 0;
        double[] quarters = low == 4 ? new double[4] : null;
        for (int t = 0; t < SAMPLES; t++) {
            int a = pixelIndex(base, low, t);
            int b = pixelIndex(base, high, t);
            float strength = (float) Math.min(52, distance(work.pixels, a, b));
            work.sorted[t] = strength;
            sum += strength;
            luma += work.pixels[b] - work.pixels[a];
            if (strength > 13) support++;
            if (quarters != null) quarters[t / 11] += strength;
        }
        Arrays.sort(work.sorted);
        output[out++] = (float) (sum / (SAMPLES * 42));
        output[out++] = (work.sorted[10] + work.sorted[11]) / 84;
        output[out++] = support / (float) SAMPLES;
        output[out++] = (float) (luma / (SAMPLES * 42));
        if (quarters != null) {
            for (int i = 0; i < 4; i++) output[out++] = (float) (quarters[i] / (42 * (i == 3 ? 10 : 11)));
        }
        return out;
    }

    private static int textureFeatures(float[] values, int base, int band, float[] output, int out) {
        double luma = 0;
        double square = 0;
        double variance = 0;
        double texture = 0;
        double contrast = 0;
        double lastL = 0;
        double lastA = 0;
        double lastB = 0;
        for (int t = 0; t < SAMPLES; t++) {
            int first = pixelIndex(base, band, t);
            int middle = pixelIndex(base, band + 1, t);
            int last = pixelIndex(base, band + 2, t);
            double meanL = (values[first] + values[middle] + values[last]) / 3.0;
            double meanA = (values[first + 1] + values[middle + 1] + values[last + 1]) / 3.0;
            double meanB = (values[first + 2] + values[middle + 2] + values[last + 2]) / 3.0;
            luma += meanL;
            square += meanL * meanL;
            variance += squares(values, first, meanL, meanA, meanB)
                    + squares(values, middle, meanL, meanA, meanB) + squares(values, last, meanL, meanA, meanB);
            if (t > 0) texture += magnitude(meanL - lastL, meanA - lastA, meanB - lastB);
            contrast += distance(values, first, last);
            lastL = meanL;
            lastA = meanA;
            lastB = meanB;
        }
        double mean = luma / SAMPLES;
        output[out++] = (float) ((mean - 128) / 65);
        output[out++] = (float) (Math.sqrt(Math.max(0, square / SAMPLES - mean * mean)) / 45);
        output[out++] = (float) (Math.sqrt(variance / (9 * SAMPLES)) / 24);
        output[out++] = (float) (texture / (30 * (SAMPLES - 1)));
        output[out++] = (float) (contrast / (35 * SAMPLES));
        return out;
    }

    private static double squares(float[] values, int index, double l, double a, double b) {
        double dl = values[index] - l;
        double da = values[index + 1] - a;
        double db = values[index + 2] - b;
        return dl * dl + da * da + db * db;
    }

    private static int pixelIndex(int base, int band, int t) {
        return base + (band * SAMPLES + t) * 3;
    }

    private static double distance(float[] values, int first, int second) {
        return magnitude(values[second] - values[first], values[second + 1] - values[first + 1],
                values[second + 2] - values[first + 2]);
    }

    private static double magnitude(double l, double a, double b) {
        return Math.sqrt(l * l + 0.3969 * (a * a + b * b));
    }

    private static float center(int candidate) {
        return (candidate % CENTERS - 12) * 0.01f;
    }

    private static float slope(int candidate) {
        return (candidate / CENTERS - 8) * 0.01875f;
    }

    private static float[] decode(FloatBuffer logits, float[] proposal, Mat transform, int width, int height,
                                  int margin, int span, int sourceWidth, int sourceHeight) {
        Point[][] lines = new Point[4][];
        for (int side = 0; side < 4; side++) {
            int best = 0;
            int start = side * CANDIDATES;
            for (int i = 1; i < CANDIDATES; i++) {
                if (logits.get(start + i) > logits.get(start + best)) best = i;
            }
            float offset = center(best);
            float slope = slope(best);
            if (offset + Math.abs(slope) / 2 > 0.095f) {
                offset = 0;
                slope = 0;
            } else {
                // A small exterior allowance protects edge detail from sampling error.
                offset -= 0.006f;
            }
            boolean swap = side % 2 != 0;
            boolean flip = side == 1 || side == 2;
            int length = swap ? height : width;
            int transverse = swap ? width : height;
            double first = margin + span * (offset - slope / 2);
            double last = margin + span * (offset + slope / 2);
            if (flip) {
                first = transverse + 2 * margin - first;
                last = transverse + 2 * margin - last;
            }
            lines[side] = swap ? new Point[]{new Point(first, margin), new Point(last, margin + length)}
                    : new Point[]{new Point(margin, first), new Point(margin + length, last)};
        }
        Point[] points = new Point[4];
        for (int i = 0; i < 4; i++) {
            points[i] = meet(lines[(i + 3) % 4], lines[i]);
            if (points[i] == null) return proposal;
        }
        MatOfPoint2f rectified = new MatOfPoint2f(points);
        MatOfPoint2f original = new MatOfPoint2f();
        Mat inverse = transform.inv();
        try {
            Core.perspectiveTransform(rectified, original, inverse);
            Point[] corners = original.toArray();
            float[] result = new float[8];
            for (int i = 0; i < 4; i++) {
                result[2 * i] = (float) (corners[i].x / sourceWidth);
                result[2 * i + 1] = (float) (corners[i].y / sourceHeight);
            }
            return CropGeometry.valid(result) && CropGeometry.iou(result, proposal) >= 0.71 ? result : proposal;
        } finally {
            inverse.release();
            original.release();
            rectified.release();
        }
    }

    private static Point meet(Point[] first, Point[] second) {
        Point a = first[0];
        Point b = second[0];
        double dx = first[1].x - a.x;
        double dy = first[1].y - a.y;
        double ox = second[1].x - b.x;
        double oy = second[1].y - b.y;
        double determinant = dx * oy - dy * ox;
        if (Math.abs(determinant) < 1e-6) return null;
        double t = ((b.x - a.x) * oy - (b.y - a.y) * ox) / determinant;
        return new Point(a.x + t * dx, a.y + t * dy);
    }

    private static double edgeLength(float[] points, int a, int b, int c, int d) {
        return Math.max(Math.hypot(points[a] - points[b], points[a + 1] - points[b + 1]),
                Math.hypot(points[c] - points[d], points[c + 1] - points[d + 1]));
    }
}

