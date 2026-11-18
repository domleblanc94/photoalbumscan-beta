package local.photoscan;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtProvider;
import ai.onnxruntime.OrtSession;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** On-device dense corner predictor. Coordinates are decoded in the original image space. */
final class QuadModel implements AutoCloseable {
    static final int SIZE = 384;
    private static final int STRIDE = 8;
    private static final int GRID = SIZE / STRIDE;
    private static final int GRID_AREA = GRID * GRID;
    private static final int PIXELS = SIZE * SIZE;
    private static final int PADDING = 0xff727272;
    private static final float CONFIDENCE = 0.28f;
    private static final double BASELINE_LOGIT = Math.log(CONFIDENCE / (1 - CONFIDENCE));
    private static final double SUPPLEMENT_LOGIT = Math.log(.14 / .86);
    private static final int MAX_BASELINE_PEAKS = 42;
    private static final int MAX_BASELINE_PROPOSALS = 6;
    private static final int MAX_SUPPLEMENT = 2;
    private static final long[] SHAPE = {1, 3, SIZE, SIZE};
    private static final String ASSET = "print-detector.onnx";

    record Prediction(float[] quad, float confidence) {}
    record Bounds(int left, int top, int width, int height) {}
    private record Peak(int index, float logit) {}

    private static final class InputBuffer {
        final Bitmap bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        final int[] pixels = new int[PIXELS];
        final FloatBuffer rgb = ByteBuffer.allocateDirect(3 * PIXELS * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    private final ThreadLocal<InputBuffer> inputs = ThreadLocal.withInitial(InputBuffer::new);
    private final OrtEnvironment environment;
    // Keep options alive while the session uses their allocated resources.
    private final OrtSession.SessionOptions options;
    private final OrtSession session;
    final String backend;

    private QuadModel(byte[] weights, int threads) throws OrtException {
        environment = OfflineOrt.environment();
        options = new OrtSession.SessionOptions();
        boolean xnnpack = OrtEnvironment.getAvailableProviders().contains(OrtProvider.XNNPACK);
        try {
            options.setInterOpNumThreads(1);
            options.setIntraOpNumThreads(xnnpack ? 1 : threads);
            options.addConfigEntry("session.intra_op.allow_spinning", "0");
            if (xnnpack) options.addXnnpack(Collections.singletonMap("intra_op_num_threads", Integer.toString(threads)));
            session = environment.createSession(weights, options);
        } catch (OrtException | RuntimeException failure) {
            options.close();
            throw failure;
        }
        backend = xnnpack ? "XNNPACK" : "CPU";
    }

    static QuadModel load(Context context) throws IOException, OrtException {
        try (InputStream stream = context.getAssets().open(ASSET)) {
            return load(stream);
        } catch (FileNotFoundException absent) {
            return null;
        }
    }

    static QuadModel load(InputStream stream) throws IOException, OrtException {
        return load(stream, 2);
    }

    static QuadModel loadSavedLocator(Context context) throws IOException, OrtException {
        try (InputStream stream = context.getAssets().open(ASSET)) {
            return load(stream, 1);
        }
    }

    private static QuadModel load(InputStream stream, int threads) throws IOException, OrtException {
        ByteArrayOutputStream weights = new ByteArrayOutputStream();
        byte[] buffer = new byte[32 * 1024];
        for (int count; (count = stream.read(buffer)) != -1;) weights.write(buffer, 0, count);
        return new QuadModel(weights.toByteArray(), threads);
    }

    List<Prediction> detect(Bitmap bitmap) throws OrtException {
        return detect(bitmap, false);
    }

    List<Prediction> detect(Bitmap bitmap, boolean supplement) throws OrtException {
        InputBuffer input = inputs.get();
        Bounds bounds = drawLetterbox(bitmap, input.canvas);
        input.bitmap.getPixels(input.pixels, 0, SIZE, 0, 0, SIZE, SIZE);
        for (int i = 0; i < PIXELS; i++) {
            int argb = input.pixels[i];
            input.rgb.put(i, ((argb >>> 16) & 255) / 255f);
            input.rgb.put(i + PIXELS, ((argb >>> 8) & 255) / 255f);
            input.rgb.put(i + 2 * PIXELS, (argb & 255) / 255f);
        }
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, input.rgb, SHAPE);
             OrtSession.Result result = session.run(Collections.singletonMap("image", tensor))) {
            FloatBuffer heatmap = ((OnnxTensor) result.get("center").orElseThrow()).getFloatBuffer();
            FloatBuffer offsets = ((OnnxTensor) result.get("offsets").orElseThrow()).getFloatBuffer();
            return decode(heatmap, offsets, bounds, supplement);
        }
    }

    static Bounds drawLetterbox(Bitmap bitmap, Canvas canvas) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        double scale = (double) SIZE / Math.max(width, height);
        int scaledWidth = Math.max(1, (int) Math.round(width * scale));
        int scaledHeight = Math.max(1, (int) Math.round(height * scale));
        Bounds bounds = new Bounds((SIZE - scaledWidth) / 2, (SIZE - scaledHeight) / 2,
                scaledWidth, scaledHeight);
        canvas.drawColor(PADDING);
        Rect target = new Rect(bounds.left(), bounds.top(), bounds.left() + scaledWidth,
                bounds.top() + scaledHeight);
        canvas.drawBitmap(bitmap, null, target, new Paint(Paint.FILTER_BITMAP_FLAG));
        return bounds;
    }

    @Override public void close() throws OrtException {
        try {
            session.close();
        } finally {
            options.close();
        }
    }

    static List<Prediction> decode(FloatBuffer heatmap, FloatBuffer offsets, Bounds bounds, boolean supplement) {
        List<Peak> peaks = new ArrayList<>();
        double minLogit = supplement ? SUPPLEMENT_LOGIT : BASELINE_LOGIT;
        for (int y = 0; y < GRID; y++) {
            for (int x = 0; x < GRID; x++) {
                int cell = y * GRID + x;
                float logit = heatmap.get(cell);
                if (logit >= minLogit && isPeak(heatmap, x, y, logit)) peaks.add(new Peak(cell, logit));
            }
        }
        peaks.sort(Comparator.comparingDouble(Peak::logit).reversed());
        List<Prediction> result = new ArrayList<>(MAX_BASELINE_PROPOSALS);
        for (int i = 0; i < Math.min(MAX_BASELINE_PEAKS, peaks.size()); i++) {
            Peak peak = peaks.get(i);
            if (peak.logit() < BASELINE_LOGIT) break;
            append(result, offsets, bounds, peak);
            if (result.size() == MAX_BASELINE_PROPOSALS) break;
        }
        if (supplement) {
            int cap = result.size() + MAX_SUPPLEMENT;
            for (Peak peak : peaks) {
                if (peak.logit() >= BASELINE_LOGIT) continue;
                append(result, offsets, bounds, peak);
                if (result.size() == cap) break;
            }
        }
        return result;
    }

    private static void append(List<Prediction> result, FloatBuffer offsets, Bounds bounds, Peak peak) {
        int cell = peak.index();
        float cx = (cell % GRID + 0.5f) * STRIDE;
        float cy = (cell / GRID + 0.5f) * STRIDE;
        float[] quad = new float[8];
        for (int corner = 0; corner < 4; corner++) {
            float x = (offsets.get(2 * corner * GRID_AREA + cell) * SIZE + cx - bounds.left()) / bounds.width();
            float y = (offsets.get((2 * corner + 1) * GRID_AREA + cell) * SIZE + cy - bounds.top()) / bounds.height();
            if (x < -0.025f || y < -0.025f || x > 1.025f || y > 1.025f) return;
            quad[2 * corner] = Math.max(0, Math.min(1, x));
            quad[2 * corner + 1] = Math.max(0, Math.min(1, y));
        }
        float[] ordered = CropGeometry.canonical(quad);
        if (!CropGeometry.valid(ordered) || CropGeometry.area(ordered) < 0.008) return;
        if (result.stream().anyMatch(previous -> CropGeometry.iou(ordered, previous.quad()) > 0.27)) return;
        result.add(new Prediction(ordered, (float) (1 / (1 + Math.exp(-peak.logit())))));
    }

    private static boolean isPeak(FloatBuffer heatmap, int x, int y, float value) {
        for (int ny = Math.max(0, y - 1); ny <= Math.min(GRID - 1, y + 1); ny++) {
            for (int nx = Math.max(0, x - 1); nx <= Math.min(GRID - 1, x + 1); nx++) {
                if (heatmap.get(ny * GRID + nx) > value) return false;
            }
        }
        return true;
    }
}



