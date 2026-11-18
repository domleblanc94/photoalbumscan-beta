package local.photoscan;

import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.util.Size;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Pixel-center geometry and exact RGB8 warp used by the saved-page models. */
final class PictureModelInput {
    static final int PAGE = 512;
    static final int ROI = 384;
    static final double DEFAULT_ROI_PADDING = .30;
    static final int LEFT = 1, TOP = 2, RIGHT = 4, BOTTOM = 8;
    private static final int EDGE_CONTEXT = 16;
    private PictureModelInput() {}

    static Size uprightSize(File original) throws IOException {
        Size[] size = new Size[1];
        Bitmap header = ImageDecoder.decodeBitmap(ImageDecoder.createSource(original), (decoder, info, source) -> {
            size[0] = info.getSize();
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setTargetSize(1, 1);
        });
        header.recycle();
        if (size[0].getWidth() < 2 || size[0].getHeight() < 2) throw new IOException("Image is too small.");
        return size[0];
    }

    static double[] pageTransform(Size source) {
        int width = source.getWidth() - 1, height = source.getHeight() - 1;
        double scale = (PAGE - 1.) / Math.max(width, height);
        return new double[]{scale, 0, (PAGE - 1. - width * scale) / 2,
                0, scale, (PAGE - 1. - height * scale) / 2};
    }

    static double[] roiTransform(Size source, PictureQuadFit.Vertex[] quad) {
        return roiTransform(source, quad, DEFAULT_ROI_PADDING);
    }

    static void requireRoiPadding(double padding) {
        if (padding != DEFAULT_ROI_PADDING && padding != .50 && padding != .75) {
            throw new IllegalArgumentException("ROI padding must be 0.30, 0.50 or 0.75.");
        }
    }

    static double[] roiTransform(Size source, PictureQuadFit.Vertex[] quad, double padding) {
        requireRoiPadding(padding);
        double[] bounds = requestedRoiBounds(quad, padding);
        double minX = Math.max(0, bounds[0]), maxX = Math.min(source.getWidth() - 1, bounds[2]);
        double minY = Math.max(0, bounds[1]), maxY = Math.min(source.getHeight() - 1, bounds[3]);
        if (maxX - minX <= 1 || maxY - minY <= 1) return null;
        double sx = (ROI - 1.) / (maxX - minX), sy = (ROI - 1.) / (maxY - minY);
        return new double[]{sx, 0, -minX * sx, 0, sy, -minY * sy};
    }

    private static double[] requestedRoiBounds(PictureQuadFit.Vertex[] quad, double padding) {
        double minX = Double.POSITIVE_INFINITY, minY = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX;
        for (var v : quad) {
            minX = Math.min(minX, v.x()); maxX = Math.max(maxX, v.x());
            minY = Math.min(minY, v.y()); maxY = Math.max(maxY, v.y());
        }
        double mx = padding * (maxX - minX), my = padding * (maxY - minY);
        return new double[]{minX - mx, minY - my, maxX + mx, maxY + my};
    }

    static double[] sourceEdgeRetryTransform(Size source, PictureQuadFit.Vertex[] proposal,
                                            double[] first, int contacted) {
        return sourceEdgeRetryTransform(source, proposal, first, contacted, .50);
    }

    static double[] sourceEdgeRetryTransform(Size source, PictureQuadFit.Vertex[] proposal,
                                            double[] first, int contacted, double requestedPadding) {
        requireRoiPadding(requestedPadding);
        if (first == null || first.length != 6) return null;
        double[] bounds = requestedRoiBounds(proposal, requestedPadding);
        int clipped = (bounds[0] < 0 ? LEFT : 0) | (bounds[1] < 0 ? TOP : 0)
                | (bounds[2] > source.getWidth() - 1 ? RIGHT : 0)
                | (bounds[3] > source.getHeight() - 1 ? BOTTOM : 0);
        if (contacted == 0 || (contacted & ~clipped) != 0) return null;
        for (double value : first) if (!Double.isFinite(value)) return null;
        double[] retry = first.clone();
        remapAxis(retry, 0, (contacted & LEFT) != 0, (contacted & RIGHT) != 0);
        remapAxis(retry, 3, (contacted & TOP) != 0, (contacted & BOTTOM) != 0);
        return retry;
    }

    private static void remapAxis(double[] transform, int axis, boolean low, boolean high) {
        if (!low && !high) return;
        double last = ROI - 1.;
        double scale = (last - EDGE_CONTEXT * ((low ? 1 : 0) + (high ? 1 : 0))) / last;
        for (int i = axis; i < axis + 3; i++) transform[i] *= scale;
        if (low) transform[axis + 2] += EDGE_CONTEXT;
    }

    record Rgb(int width, int height, byte[] pixels) {}

    static Rgb previewRgb(File original, int limit) throws IOException {
        Bitmap preview = ImageEngine.preview(original, limit);
        try {
            int w = preview.getWidth(), h = preview.getHeight();
            byte[] rgb = new byte[w * h * 3];
            int[] argb = new int[w];
            for (int y = 0, i = 0; y < h; y++) {
                preview.getPixels(argb, 0, w, 0, y, w, 1);
                for (int value : argb) {
                    rgb[i++] = (byte) (value >>> 16);
                    rgb[i++] = (byte) (value >>> 8);
                    rgb[i++] = (byte) value;
                }
            }
            return new Rgb(w, h, rgb);
        } finally {
            preview.recycle();
        }
    }

    static byte[] warp(Rgb preview, Size source, double[] a, int size) {
        // The frozen OpenCV 5 host uses higher-precision sampling than Android
        // OpenCV 4's warpPerspective. Sample the same affine without its 1/32 grid.
        double sx = (double) preview.width() / source.getWidth();
        double sy = (double) preview.height() / source.getHeight();
        int[] left = new int[size], right = new int[size];
        double[] horizontal = new double[size];
        for (int x = 0; x < size; x++) {
            double px = ((x - a[2]) / a[0] + .5) * sx - .5;
            int ix = (int) Math.floor(px);
            horizontal[x] = px - ix;
            left[x] = ix >= 0 && ix < preview.width() ? ix * 3 : -1;
            right[x] = ix + 1 >= 0 && ix + 1 < preview.width() ? (ix + 1) * 3 : -1;
        }
        byte[] out = new byte[size * size * 3];
        byte[] pixels = preview.pixels();
        int stride = preview.width() * 3;
        for (int y = 0, at = 0; y < size; y++) {
            double py = ((y - a[5]) / a[4] + .5) * sy - .5;
            int iy = (int) Math.floor(py);
            double wy = py - iy;
            int top = iy >= 0 && iy < preview.height() ? iy * stride : -1;
            int bottom = iy + 1 >= 0 && iy + 1 < preview.height() ? (iy + 1) * stride : -1;
            for (int x = 0; x < size; x++) {
                double wx = horizontal[x];
                int l = left[x], r = right[x];
                for (int channel = 0; channel < 3; channel++) {
                    double above = (1 - wx) * pixel(pixels, top, l, channel)
                            + wx * pixel(pixels, top, r, channel);
                    double below = (1 - wx) * pixel(pixels, bottom, l, channel)
                            + wx * pixel(pixels, bottom, r, channel);
                    out[at++] = (byte) Math.rint((1 - wy) * above + wy * below);
                }
            }
        }
        return out;
    }

    private static int pixel(byte[] bytes, int row, int column, int channel) {
        return row < 0 || column < 0 ? 114 : bytes[row + column + channel] & 255;
    }

    static FloatBuffer buffer(int size, int channels) {
        return ByteBuffer.allocateDirect(size * size * channels * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    static void pack(byte[] rgb, FloatBuffer input, int size, float cx, float cy) {
        int area = size * size;
        for (int i = 0; i < area; i++) {
            for (int c = 0; c < 3; c++) input.put(c * area + i, (rgb[i * 3 + c] & 255) / 255f);
        }
        if (input.capacity() == area * 4) {
            float sigma = size / 8f;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    float dx = x - cx, dy = y - cy;
                    input.put(3 * area + y * size + x, (float) Math.exp(-(dx * dx + dy * dy) / (2 * sigma * sigma)));
                }
            }
        }
    }
}

