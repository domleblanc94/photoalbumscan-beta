package local.photoscan;

import ai.onnxruntime.OrtException;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import androidx.exifinterface.media.ExifInterface;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;

public final class ImageEngine {
    private static final long MEBIBYTE = 1024L * 1024;
    private static volatile boolean ready;

    private static volatile QuadModel model;
    private static volatile BoundaryRefiner edgeRefiner;

    private ImageEngine() {}

    public static synchronized void initialize(Context context) throws Exception {
        if (ready) return;
        if (!OpenCVLoader.initLocal()) throw new IOException("Could not load the on-device crop detector.");
        try {
            model = QuadModel.load(context);
        } catch (IOException | OrtException | LinkageError | RuntimeException error) {
            android.util.Log.w("PhotoScanImaging", "Could not load the trained crop model; using the classical detector.", error);
        }
        try {
            edgeRefiner = BoundaryRefiner.load(context);
        } catch (IOException | OrtException | LinkageError | RuntimeException error) {
            android.util.Log.w("PhotoScanImaging", "Boundary refinement unavailable; keeping original suggestions.", error);
        }
        ready = true;
    }

    public static Bitmap preview(File original, int maxEdge) throws IOException {
        if (maxEdge < 1) throw new IllegalArgumentException("Preview size must be positive.");
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(original), (decoder, info, source) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            int width = info.getSize().getWidth();
            int height = info.getSize().getHeight();
            double scale = Math.min(1.0, (double) maxEdge / Math.max(width, height));
            decoder.setTargetSize(Math.max(1, (int) Math.round(width * scale)),
                    Math.max(1, (int) Math.round(height * scale)));
        });
    }

    public static List<float[]> detect(Bitmap uprightPreview) {
        return detect(uprightPreview, false);
    }

    /** Fast, non-blocking with respect to the review pipeline. Run on the camera analysis worker. */
    public static List<float[]> detectLive(Bitmap uprightPreview) {
        return detect(uprightPreview, true);
    }

    private static List<float[]> detect(Bitmap uprightPreview, boolean live) {
        List<QuadModel.Prediction> predictions = modelPredictions(uprightPreview);
        if (predictions == null) return detectClassical(uprightPreview, live);
        List<float[]> quads = new ArrayList<>(predictions.size());
        for (QuadModel.Prediction prediction : predictions) quads.add(prediction.quad());
        return quads;
    }

    static List<QuadModel.Prediction> modelPredictions(Bitmap uprightPreview) {
        QuadModel available = model;
        if (available == null) return null;
        try {
            return available.detect(uprightPreview);
        } catch (OrtException | RuntimeException error) {
            model = null;
            android.util.Log.w("PhotoScanImaging", "Trained crop detection failed; using the classical detector.", error);
            return null;
        }
    }

    static List<float[]> detectClassical(Bitmap uprightPreview, boolean live) {
        if (!ready) throw new IllegalStateException("Initialize the imaging engine before detecting crops.");
        Mat rgba = new Mat();
        try {
            Utils.bitmapToMat(uprightPreview, rgba);
            return Detector.detect(rgba, live);
        } finally {
            rgba.release();
        }
    }

    /** Review-only edge refinement. Preserves proposal order and falls back on unsupported images. */
    public static List<float[]> refineBoundaries(File original, List<float[]> proposals) {
        BoundaryRefiner available = edgeRefiner;
        if (available == null || model == null || proposals.isEmpty()) return proposals;
        Bitmap detail = null;
        try {
            detail = preview(original, 2000);
            return refineBoundaries(detail, proposals);
        } catch (IOException | RuntimeException | OutOfMemoryError failure) {
            android.util.Log.w("PhotoScanImaging", "Could not refine crop boundaries.", failure);
            return proposals;
        } finally {
            if (detail != null) detail.recycle();
        }
    }

    static List<float[]> refineBoundaries(Bitmap detail, List<float[]> proposals) {
        BoundaryRefiner available = edgeRefiner;
        if (available == null || proposals.isEmpty()) return proposals;
        Mat rgba = new Mat();
        try {
            Utils.bitmapToMat(detail, rgba);
            return available.refine(rgba, proposals);
        } catch (OrtException | RuntimeException | OutOfMemoryError failure) {
            android.util.Log.w("PhotoScanImaging", "Could not refine crop boundaries.", failure);
            return proposals;
        } finally {
            rgba.release();
        }
    }

    /** Returns a relative detail score, or NaN if there is no usable crop interior. */
    public static float sharpness(Bitmap uprightPreview, List<float[]> quads) {
        if (!ready) throw new IllegalStateException("Initialize the imaging engine before assessing sharpness.");
        if (quads.isEmpty()) return Float.NaN;
        Mat rgba = new Mat();
        try {
            Utils.bitmapToMat(uprightPreview, rgba);
            return Detector.sharpness(rgba, quads);
        } finally {
            rgba.release();
        }
    }

    public static boolean validQuad(float[] q) {
        return CropGeometry.valid(q);
    }

    public static Bitmap render(File original, float[] q, int clockwiseTurns, float reflectionStrength,
                                int maxEdge) throws IOException {
        return render(original, q, clockwiseTurns, reflectionStrength, maxEdge, 0);
    }

    /** Returns null when the native crop cannot exceed the requested minimum edge. */
    static Bitmap renderRotationInput(File original, float[] q, int maxEdge, int minNativeEdge)
            throws IOException {
        return render(original, q, 0, 0f, maxEdge, minNativeEdge);
    }

    private static Bitmap render(File original, float[] q, int clockwiseTurns, float reflectionStrength,
                                 int maxEdge, int minNativeEdge) throws IOException {
        if (!validQuad(q)) throw new IOException("Adjust the four corners to form a valid crop.");
        if (maxEdge < 0 || !Float.isFinite(reflectionStrength)
                || reflectionStrength < 0 || reflectionStrength > 1) {
            throw new IllegalArgumentException("Invalid render options.");
        }
        int orientation = new ExifInterface(original).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(original.getAbsolutePath(), false);
        if (decoder == null) throw new IOException("This image format cannot be cropped on this device.");
        Bitmap source = null;
        Bitmap output = null;
        boolean success = false;
        try {
            int rawWidth = decoder.getWidth();
            int rawHeight = decoder.getHeight();
            float[] points = new float[8];
            float minX = rawWidth;
            float minY = rawHeight;
            float maxX = 0;
            float maxY = 0;
            for (int i = 0; i < 8; i += 2) {
                float[] raw = CropGeometry.toRaw(q[i], q[i + 1], orientation);
                float x = points[i] = raw[0] * rawWidth;
                float y = points[i + 1] = raw[1] * rawHeight;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
            int width = CropGeometry.sourceLength(points, 0, 2, 6, 4);
            int height = CropGeometry.sourceLength(points, 0, 6, 2, 4);
            if (width < 2 || height < 2) throw new IOException("The crop is too small to export.");
            int nativeEdge = Math.max(width, height);
            if (nativeEdge <= minNativeEdge) return null;

            double scale = maxEdge == 0 ? 1 : Math.min(1, (double) maxEdge / nativeEdge);
            width = Math.max(1, (int) Math.round(width * scale));
            height = Math.max(1, (int) Math.round(height * scale));
            int turns = Math.floorMod(clockwiseTurns, 4);
            int outputWidth = turns % 2 == 0 ? width : height;
            int outputHeight = turns % 2 == 0 ? height : width;

            // Include a small interpolation margin while keeping the decode region inside the file.
            Rect bounds = new Rect(Math.max(0, (int) Math.floor(minX) - 2),
                    Math.max(0, (int) Math.floor(minY) - 2),
                    Math.min(rawWidth, (int) Math.ceil(maxX) + 2),
                    Math.min(rawHeight, (int) Math.ceil(maxY) + 2));
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inSampleSize = 1;
            while (options.inSampleSize <= 256 && 2 * options.inSampleSize <= 1.0 / scale) {
                options.inSampleSize *= 2;
            }
            long sampledWidth = (bounds.width() + (long) options.inSampleSize - 1) / options.inSampleSize;
            long sampledHeight = (bounds.height() + (long) options.inSampleSize - 1) / options.inSampleSize;
            long estimate = 4L * (sampledWidth * sampledHeight + (long) outputWidth * outputHeight);
            long limit = Math.min(192 * MEBIBYTE, Runtime.getRuntime().maxMemory() * 2 / 3);
            if (estimate > limit) throw new IOException("This crop is too large to render at its requested resolution.");
            source = decoder.decodeRegion(bounds, options);
            if (source == null) throw new IOException("Could not decode the original crop pixels.");
            float sx = (float) source.getWidth() / bounds.width();
            float sy = (float) source.getHeight() / bounds.height();
            for (int i = 0; i < 8; i += 2) {
                points[i] = (points[i] - bounds.left) * sx;
                points[i + 1] = (points[i + 1] - bounds.top) * sy;
            }
            float[] corners = {0, 0, outputWidth, 0, outputWidth, outputHeight, 0, outputHeight};
            float[] destination = new float[8];
            for (int i = 0; i < 4; i++) {
                int j = (i + turns) % 4;
                destination[2 * i] = corners[2 * j];
                destination[2 * i + 1] = corners[2 * j + 1];
            }
            Matrix transform = new Matrix();
            if (!transform.setPolyToPoly(points, 0, destination, 0, 4)) {
                throw new IOException("Could not straighten this crop. Check its corners.");
            }
            output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);
            canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(source, transform, new Paint(Paint.FILTER_BITMAP_FLAG));
            source.recycle();
            source = null;
            if (reflectionStrength > 0) softenHighlights(output, reflectionStrength);
            success = true;
            return output;
        } catch (OutOfMemoryError error) {
            throw new IOException("Not enough memory to render this crop at its requested resolution.", error);
        } finally {
            if (!success && output != null) output.recycle();
            if (source != null) source.recycle();
            decoder.recycle();
        }
    }

    /** Face-based automatic rotation is unavailable in the open-source beta. */
    public static int suggestRotation(Bitmap rectified) { return -1; }

    private static void softenHighlights(Bitmap output, float strength) {
        int width = output.getWidth();
        int height = output.getHeight();
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            output.getPixels(row, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x++) {
                int pixel = row[x];
                int r = Color.red(pixel);
                int g = Color.green(pixel);
                int b = Color.blue(pixel);
                int low = Math.min(r, Math.min(g, b));
                int high = Math.max(r, Math.max(g, b));
                // A mild tone adjustment for near-neutral bright haze. Keep peak whites.
                float bright = Math.max(0, Math.min(1, (low - 148) / 60f));
                float neutral = Math.max(0, 1 - (high - low) / 55f);
                float taper = Math.max(0, Math.min(1, (251 - high) / 30f));
                int lift = Math.round(15 * strength * bright * neutral * taper);
                if (lift > 0) row[x] = Color.rgb(r - lift, g - lift, b - lift);
            }
            output.setPixels(row, 0, width, 0, y, width, 1);
        }
    }
}




