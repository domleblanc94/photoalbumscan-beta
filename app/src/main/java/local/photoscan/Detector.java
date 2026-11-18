package local.photoscan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.LineSegmentDetector;

final class Detector {
    private static final int ANALYSIS_EDGE = 960;
    private static final int LIVE_EDGE = 640;
    private static final double[] PRINT_RATIOS = {1, 4.0 / 5, 3.0 / 4, 5.0 / 7, 2.0 / 3, 9.0 / 16};
    private static final int MAX_PRINTS = 6;

    private record Candidate(Point[] corners, double area, double score) {}
    private record Line(Point start, Point end, double dx, double dy, double length) {}
    private record LinePair(Line first, Line second) {}

    private Detector() {}

    static List<float[]> detect(Mat rgba, boolean live) {
        List<Candidate> candidates = new ArrayList<>();
        Mat image = new Mat();
        Mat gray = new Mat();
        Mat channel = new Mat();
        Mat smooth = new Mat();
        Mat edges = new Mat();
        Mat colorEdges = new Mat();
        Mat closed = new Mat();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
        try {
            double scale = Math.min(1, (double) (live ? LIVE_EDGE : ANALYSIS_EDGE) / Math.max(rgba.cols(), rgba.rows()));
            Imgproc.resize(rgba, image, new Size(Math.max(1, Math.round(rgba.cols() * scale)),
                    Math.max(1, Math.round(rgba.rows() * scale))), 0, 0, Imgproc.INTER_AREA);
            int width = image.cols();
            int height = image.rows();
            if (width < 40 || height < 40) return java.util.Collections.emptyList();
            Imgproc.cvtColor(image, gray, Imgproc.COLOR_RGBA2GRAY);
            Imgproc.GaussianBlur(gray, smooth, new Size(5, 5), 0.9);
            byte[] values = new byte[width * height];
            smooth.get(0, 0, values);

            if (live) {
                Imgproc.Canny(smooth, edges, 12, 42);
                Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, kernel);
                addContours(closed, values, width, height, candidates);
                for (int threshold : new int[]{75, 155, 195}) {
                    Imgproc.threshold(gray, edges, threshold, 255, Imgproc.THRESH_BINARY);
                    addContours(edges, values, width, height, candidates);
                }
                addUniformBorderCandidates(image, values, width, height, candidates, 2);
                addLineCandidates(gray, values, width, height, candidates, true);
                return select(candidates, width, height);
            }

            Imgproc.Canny(smooth, edges, 25, 70);
            addContours(edges, values, width, height, candidates);
            Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, kernel);
            addContours(closed, values, width, height, candidates);

            Imgproc.Canny(smooth, edges, 12, 40);
            addContours(edges, values, width, height, candidates);
            Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, kernel);
            addContours(closed, values, width, height, candidates);

            colorEdges.create(height, width, CvType.CV_8UC1);
            colorEdges.setTo(org.opencv.core.Scalar.all(0));
            for (int c = 0; c < 3; c++) {
                Core.extractChannel(image, channel, c);
                Imgproc.GaussianBlur(channel, smooth, new Size(5, 5), 0.9);
                Imgproc.Canny(smooth, edges, 20, 60);
                Core.bitwise_or(colorEdges, edges, colorEdges);
            }
            addContours(colorEdges, values, width, height, candidates);
            Imgproc.morphologyEx(colorEdges, closed, Imgproc.MORPH_CLOSE, kernel);
            addContours(closed, values, width, height, candidates);

            for (int threshold : new int[]{45, 85, 125, 165, 205}) {
                Imgproc.threshold(gray, edges, threshold, 255, Imgproc.THRESH_BINARY);
                addContours(edges, values, width, height, candidates);
            }
            Imgproc.GaussianBlur(gray, smooth, new Size(9, 9), 2.2);
            for (int high : new int[]{20, 40, 68}) {
                Imgproc.Canny(smooth, edges, high / 3, high);
                Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, kernel);
                addContours(closed, values, width, height, candidates);
            }
            Core.absdiff(gray, smooth, channel);
            Imgproc.blur(channel, closed, new Size(19, 19));
            for (int threshold : new int[]{3, 5, 7}) {
                Imgproc.threshold(closed, edges, threshold, 255, Imgproc.THRESH_BINARY_INV);
                addContours(edges, values, width, height, candidates);
            }
            addUniformBorderCandidates(image, values, width, height, candidates, 5);
            for (float[] proposal : detect(rgba, true)) {
                Point[] corners = new Point[4];
                for (int i = 0; i < 4; i++) {
                    corners[i] = new Point(proposal[2 * i] * width - 0.5,
                            proposal[2 * i + 1] * height - 0.5);
                }
                double area = polygonArea(corners);
                double score = boundaryScore(corners, values, width, height) + shapePreference(corners);
                if (score >= 0.65) {
                    candidates.add(new Candidate(corners, area, score + 0.12 * Math.sqrt(area / (width * height))));
                }
            }
            addLineCandidates(gray, values, width, height, candidates, false);
            return select(candidates, width, height);
        } finally {
            image.release();
            gray.release();
            channel.release();
            smooth.release();
            edges.release();
            colorEdges.release();
            closed.release();
            kernel.release();
        }
    }

    static float sharpness(Mat rgba, List<float[]> quads) {
        Mat frame = new Mat();
        Mat gray = new Mat();
        Mat laplacian = new Mat();
        try {
            double scale = Math.min(1, 384.0 / Math.max(rgba.cols(), rgba.rows()));
            if (rgba.cols() * scale < 40 || rgba.rows() * scale < 40) return Float.NaN;
            Imgproc.resize(rgba, frame, new Size(Math.round(rgba.cols() * scale),
                    Math.round(rgba.rows() * scale)), 0, 0, Imgproc.INTER_AREA);
            Imgproc.cvtColor(frame, gray, Imgproc.COLOR_RGBA2GRAY);
            Imgproc.Laplacian(gray, laplacian, CvType.CV_16S);
            int width = gray.cols();
            int height = gray.rows();
            short[] values = new short[width * height];
            laplacian.get(0, 0, values);
            List<Float> scores = new ArrayList<>();
            for (float[] quad : quads) {
                if (!CropGeometry.valid(quad)) continue;
                double total = 0;
                int count = 0;
                // Sample the print interior only. Bilinear coordinates suffice for this estimate.
                for (int y = 2; y <= 10; y++) {
                    float v = y / 12f;
                    for (int x = 2; x <= 10; x++) {
                        float u = x / 12f;
                        float topX = quad[0] + u * (quad[2] - quad[0]);
                        float topY = quad[1] + u * (quad[3] - quad[1]);
                        float bottomX = quad[6] + u * (quad[4] - quad[6]);
                        float bottomY = quad[7] + u * (quad[5] - quad[7]);
                        int px = Math.round((topX + v * (bottomX - topX)) * (width - 1));
                        int py = Math.round((topY + v * (bottomY - topY)) * (height - 1));
                        if (px < 2 || px >= width - 2 || py < 2 || py >= height - 2) continue;
                        for (int dy = -2; dy <= 2; dy++) {
                            for (int dx = -2; dx <= 2; dx++) {
                                int delta = Math.min(64, Math.abs(values[(py + dy) * width + px + dx]));
                                total += delta * delta;
                                count++;
                            }
                        }
                    }
                }
                if (count > 0) scores.add((float) Math.sqrt(total / count));
            }
            if (scores.isEmpty()) return Float.NaN;
            scores.sort(Float::compare);
            return scores.get((scores.size() - 1) / 2);
        } finally {
            frame.release();
            gray.release();
            laplacian.release();
        }
    }
    private static void addUniformBorderCandidates(Mat rgba, byte[] luminance, int width, int height,
                                                    List<Candidate> candidates, int passes) {
        Mat smooth = new Mat();
        Mat mask = new Mat();
        Mat closed = new Mat();
        int radius = Math.max(1, Math.min(width, height) / 140);
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(2 * radius + 1, 2 * radius + 1));
        try {
            Imgproc.GaussianBlur(rgba, smooth, new Size(5, 5), 0.9);
            byte[] pixels = new byte[4 * width * height];
            smooth.get(0, 0, pixels);
            int[] bins = new int[4096];
            for (int y = 4; y < height - 4; y += 3) {
                for (int x = 4; x < width - 4; x += 3) {
                    int offset = y * width + x;
                    int lum = Byte.toUnsignedInt(luminance[offset]);
                    if (lum < 98 || lum > 246
                            || Math.abs(lum - Byte.toUnsignedInt(luminance[offset + 3])) > 6
                            || Math.abs(lum - Byte.toUnsignedInt(luminance[offset + 3 * width])) > 6) continue;
                    int r = Byte.toUnsignedInt(pixels[4 * offset]);
                    int g = Byte.toUnsignedInt(pixels[4 * offset + 1]);
                    int b = Byte.toUnsignedInt(pixels[4 * offset + 2]);
                    bins[(r >> 4) * 256 + (g >> 4) * 16 + (b >> 4)]++;
                }
            }
            for (int pass = 0; pass < passes; pass++) {
                int best = 0;
                for (int i = 1; i < bins.length; i++) {
                    if (bins[i] > bins[best]) best = i;
                }
                if (bins[best] < width * height / 2900) break;
                int r = (best >> 8) * 16 + 8;
                int g = (best >> 4 & 15) * 16 + 8;
                int b = (best & 15) * 16 + 8;
                Core.inRange(smooth, new Scalar(r - 30, g - 30, b - 30, 0),
                        new Scalar(r + 30, g + 30, b + 30, 255), mask);
                Imgproc.morphologyEx(mask, closed, Imgproc.MORPH_CLOSE, kernel);
                addContours(closed, luminance, width, height, candidates);
                for (int i = 0; i < bins.length; i++) {
                    if (Math.abs((i >> 8) * 16 + 8 - r) <= 16
                            && Math.abs((i >> 4 & 15) * 16 + 8 - g) <= 16
                            && Math.abs((i & 15) * 16 + 8 - b) <= 16) bins[i] = 0;
                }
            }
        } finally {
            smooth.release();
            mask.release();
            closed.release();
            kernel.release();
        }
    }
    private static void addContours(Mat edges, byte[] values, int width, int height,
                                    List<Candidate> candidates) {
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        MatOfPoint2f curve = new MatOfPoint2f();
        MatOfPoint2f polygon = new MatOfPoint2f();
        double imageArea = (double) width * height;
        try {
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
            for (MatOfPoint contour : contours) {
                double contourArea = Math.abs(Imgproc.contourArea(contour));
                if (contourArea < 0.011 * imageArea || contourArea > 0.91 * imageArea) continue;
                curve.fromArray(contour.toArray());
                double perimeter = Imgproc.arcLength(curve, true);
                for (double epsilon : new double[]{0.012, 0.022, 0.038}) {
                    Imgproc.approxPolyDP(curve, polygon, epsilon * perimeter, true);
                    Point[] corners = polygon.toArray();
                    if (corners.length != 4) continue;
                    orderCorners(corners);
                    double area = polygonArea(corners);
                    if (area < 0.014 * imageArea || contourArea / area < 0.80
                            || contourArea / area > 1.17 || !printShape(corners, width, height)) continue;
                    double score = boundaryScore(corners, values, width, height) + shapePreference(corners);
                    if (score >= 0.68) candidates.add(new Candidate(corners, area, score + 0.12 * Math.sqrt(area / imageArea)));
                }
            }
        } finally {
            for (Mat contour : contours) contour.release();
            hierarchy.release();
            curve.release();
            polygon.release();
        }
    }

    private static void addLineCandidates(Mat gray, byte[] values, int width, int height,
                                          List<Candidate> candidates, boolean live) {
        LineSegmentDetector detector = Imgproc.createLineSegmentDetector(Imgproc.LSD_REFINE_STD);
        Mat segments = new Mat();
        Mat smoothed = new Mat();
        Mat houghEdges = new Mat();
        Mat small = new Mat();
        try {
            detector.detect(gray, segments);
            List<Line> lines = new ArrayList<>();
            double minLength = 0.085 * Math.min(width, height);
            for (int i = 0; i < segments.rows(); i++) {
                double[] p = segments.get(i, 0);
                double length = Math.hypot(p[2] - p[0], p[3] - p[1]);
                if (length >= minLength) lines.add(new Line(new Point(p[0], p[1]), new Point(p[2], p[3]),
                        (p[2] - p[0]) / length, (p[3] - p[1]) / length, length));
            }
            Imgproc.GaussianBlur(gray, smoothed, new Size(7, 7), 1.7);
            for (int high : live ? new int[0] : new int[]{24, 52}) {
                Imgproc.Canny(smoothed, houghEdges, high / 3, high);
                Imgproc.HoughLinesP(houghEdges, segments, 1, Math.PI / 360, 25,
                        minLength, 0.04 * Math.min(width, height));
                for (int i = 0; i < segments.rows(); i++) {
                    double[] p = segments.get(i, 0);
                    double length = Math.hypot(p[2] - p[0], p[3] - p[1]);
                    if (length >= minLength) lines.add(new Line(new Point(p[0], p[1]), new Point(p[2], p[3]),
                            (p[2] - p[0]) / length, (p[3] - p[1]) / length, length));
                }
            }
            Imgproc.resize(gray, small, new Size(), 0.5, 0.5, Imgproc.INTER_AREA);
            Imgproc.GaussianBlur(small, smoothed, new Size(7, 7), 1.6);
            Imgproc.Canny(smoothed, houghEdges, 7, 23);
            Imgproc.HoughLinesP(houghEdges, segments, 1, Math.PI / 360, 17,
                    minLength * 0.5, 0.03 * Math.min(width, height));
            for (int i = 0; i < segments.rows(); i++) {
                double[] p = segments.get(i, 0);
                double length = Math.hypot(p[2] - p[0], p[3] - p[1]);
                if (length * 2 >= minLength) lines.add(new Line(new Point(2 * p[0], 2 * p[1]),
                        new Point(2 * p[2], 2 * p[3]), (p[2] - p[0]) / length, (p[3] - p[1]) / length, 2 * length));
            }
            lines.sort(Comparator.comparingDouble(Line::length).reversed());
            List<Line> longest = new ArrayList<>();
            for (Line line : lines) {
                boolean duplicate = false;
                for (Line other : longest) {
                    if (Math.abs(dot(line, other)) > 0.998
                            && Math.abs(normalOffset(other, line.start())) < 3
                            && Math.abs(normalOffset(other, line.end())) < 3
                            && Math.max(projection(other, line.start()), projection(other, line.end())) >= -8
                            && Math.min(projection(other, line.start()), projection(other, line.end())) <= other.length() + 8) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) longest.add(line);
                if (longest.size() == (live ? 36 : 105)) break;
            }

            List<LinePair> pairs = new ArrayList<>();
            for (int i = 0; i < longest.size(); i++) {
                Line a = longest.get(i);
                for (int j = i + 1; j < longest.size(); j++) {
                    Line b = longest.get(j);
                    double gap = Math.abs(normalOffset(a, midpoint(b)));
                    if (Math.abs(dot(a, b)) < 0.94 || gap < minLength
                            || gap > 3.2 * Math.min(a.length(), b.length())) continue;
                    double first = projection(a, b.start());
                    double second = projection(a, b.end());
                    double common = Math.min(a.length(), Math.max(first, second))
                            - Math.max(0, Math.min(first, second));
                    if (common >= 0.34 * Math.min(a.length(), b.length())) pairs.add(new LinePair(a, b));
                }
            }
            int budget = live ? 180 : 2800;
            for (int i = 0; i < pairs.size() && budget > 0; i++) {
                LinePair a = pairs.get(i);
                for (int j = i + 1; j < pairs.size() && budget > 0; j++) {
                    LinePair b = pairs.get(j);
                    if (Math.abs(dot(a.first(), b.first())) > 0.39
                            || Math.abs(dot(a.second(), b.second())) > 0.39) continue;
                    Point p = intersection(a.first(), b.first());
                    Point q = intersection(a.first(), b.second());
                    Point r = intersection(a.second(), b.second());
                    Point s = intersection(a.second(), b.first());
                    if (!covered(a.first(), p, q) || !covered(a.second(), s, r)
                            || !covered(b.first(), p, s) || !covered(b.second(), q, r)) continue;
                    Point[] corners = {p, q, r, s};
                    boolean inFrame = true;
                    for (Point point : corners) {
                        if (!Double.isFinite(point.x) || !Double.isFinite(point.y)
                                || point.x < 2 || point.y < 2 || point.x > width - 3 || point.y > height - 3) {
                            inFrame = false;
                            break;
                        }
                    }
                    if (!inFrame) continue;
                    orderCorners(corners);
                    double area = polygonArea(corners);
                    if (area < 0.016 * width * height || area > 0.91 * width * height
                            || !printShape(corners, width, height)) continue;
                    double score = boundaryScore(corners, values, width, height) + shapePreference(corners);
                    budget--;
                    if (score >= 0.79) candidates.add(new Candidate(corners, area, score + 0.12 * Math.sqrt(area / (width * height))));
                }
            }
        } finally {
            segments.release();
            smoothed.release();
            small.release();
            houghEdges.release();
            detector.clear();
        }
    }

    private static boolean covered(Line line, Point a, Point b) {
        double first = projection(line, a);
        double second = projection(line, b);
        double start = Math.min(first, second);
        double end = Math.max(first, second);
        double length = end - start;
        double common = Math.min(end, line.length()) - Math.max(0, start);
        return common >= 0.33 * length && common >= 0.37 * line.length();
    }

    private static double dot(Line a, Line b) {
        return a.dx() * b.dx() + a.dy() * b.dy();
    }

    private static double projection(Line line, Point p) {
        return line.dx() * (p.x - line.start().x) + line.dy() * (p.y - line.start().y);
    }

    private static double normalOffset(Line line, Point p) {
        return line.dx() * (p.y - line.start().y) - line.dy() * (p.x - line.start().x);
    }

    private static Point midpoint(Line line) {
        return new Point((line.start().x + line.end().x) / 2, (line.start().y + line.end().y) / 2);
    }

    private static Point intersection(Line a, Line b) {
        double t = normalOffset(b, a.start()) / (b.dy() * a.dx() - b.dx() * a.dy());
        return new Point(a.start().x + t * a.dx(), a.start().y + t * a.dy());
    }
    private static void orderCorners(Point[] points) {
        double centerX = 0;
        double centerY = 0;
        for (Point point : points) {
            centerX += point.x / points.length;
            centerY += point.y / points.length;
        }
        final double cx = centerX;
        final double cy = centerY;
        java.util.Arrays.sort(points, Comparator.comparingDouble(p -> Math.atan2(p.y - cy, p.x - cx)));
        // Choose the cyclic ordering whose top and bottom edges read most horizontally.
        int first = 0;
        double best = -Double.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            Point a = points[i];
            Point b = points[(i + 1) % 4];
            Point c = points[(i + 2) % 4];
            Point d = points[(i + 3) % 4];
            double score = (b.x - a.x) / distance(a, b) + (c.y - b.y) / distance(b, c)
                    + (c.x - d.x) / distance(c, d) + (d.y - a.y) / distance(d, a);
            if (score > best) {
                best = score;
                first = i;
            }
        }
        Point[] sorted = points.clone();
        for (int i = 0; i < 4; i++) points[i] = sorted[(first + i) % 4];
    }

    private static double shapePreference(Point[] q) {
        double w = (distance(q[0], q[1]) + distance(q[2], q[3])) / 2;
        double h = (distance(q[1], q[2]) + distance(q[3], q[0])) / 2;
        double ratio = Math.min(w, h) / Math.max(w, h);
        double distance = Double.POSITIVE_INFINITY;
        for (double commonRatio : PRINT_RATIOS) {
            distance = Math.min(distance, Math.abs(Math.log(ratio / commonRatio)));
        }
        // Broad, capped ranking bonus only. Corner positions and output dimensions remain untouched.
        return 0.13 * Math.max(0, 1 - distance / 0.20);
    }
    private static boolean printShape(Point[] q, int width, int height) {
        double w = (distance(q[0], q[1]) + distance(q[2], q[3])) / 2;
        double h = (distance(q[1], q[2]) + distance(q[3], q[0])) / 2;
        if (Math.min(w, h) / Math.max(w, h) < 0.30) return false;
        for (int i = 0; i < 4; i++) {
            Point a = q[i];
            Point b = q[(i + 1) % 4];
            Point c = q[(i + 2) % 4];
            if (cross(a, b, c) < 0.55 * distance(a, b) * distance(b, c)) return false;
            Point d = q[(i + 3) % 4];
            double near = distance(a, b);
            double far = distance(c, d);
            double parallel = ((b.x - a.x) * (c.x - d.x) + (b.y - a.y) * (c.y - d.y)) / (near * far);
            if (parallel < 0.97 || Math.min(near, far) < 0.72 * Math.max(near, far)) return false;
            double inset = 0.009 * Math.min(width, height);
            if (a.x < inset || a.y < inset || a.x > width - inset || a.y > height - inset) return false;
            if ((a.x <= 1 && b.x <= 1) || (a.y <= 1 && b.y <= 1)
                    || (a.x >= width - 2 && b.x >= width - 2)
                    || (a.y >= height - 2 && b.y >= height - 2)) return false;
        }
        return true;
    }

    private static double boundaryScore(Point[] points, byte[] image, int width, int height) {
        double total = 0;
        double weakest = 1;
        double contrast = 0;
        double coherence = 0;
        double sleeve = 0;
        double frameRim = 0;
        for (int i = 0; i < 4; i++) {
            Point a = points[i];
            Point b = points[(i + 1) % 4];
            double length = distance(a, b);
            double nx = (b.y - a.y) / length;
            double ny = (a.x - b.x) / length;
            double edge = 0;
            double signed = 0;
            double inside = 0;
            double outside = 0;
            double insideSquare = 0;
            double outsideSquare = 0;
            for (int j = 1; j <= 20; j++) {
                double fraction = 0.06 + 0.88 * j / 21;
                double x = a.x + fraction * (b.x - a.x);
                double y = a.y + fraction * (b.y - a.y);
                double delta = 0;
                double bestWeight = 0;
                for (int offset = -2; offset <= 2; offset++) {
                    int exterior = pixel(image, width, height, x + (offset + 2) * nx, y + (offset + 2) * ny);
                    int interior = pixel(image, width, height, x + (offset - 2) * nx, y + (offset - 2) * ny);
                    double weight = Math.abs(exterior - interior) * (1 - 0.13 * Math.abs(offset));
                    if (weight > bestWeight) {
                        delta = exterior - interior;
                        bestWeight = weight;
                    }
                }
                edge += Math.min(1, bestWeight / 23);
                signed += Math.max(-1, Math.min(1, delta / 33));
                int inner = pixel(image, width, height, x - 6 * nx, y - 6 * ny);
                int outer = pixel(image, width, height, x + 6 * nx, y + 6 * ny);
                inside += inner;
                outside += outer;
                insideSquare += inner * inner;
                outsideSquare += outer * outer;
            }
            edge /= 20;
            coherence += Math.abs(signed) / 20;
            total += edge;
            weakest = Math.min(weakest, edge);
            double innerVariance = insideSquare / 20 - Math.pow(inside / 20, 2);
            double outerVariance = outsideSquare / 20 - Math.pow(outside / 20, 2);
            contrast += Math.max(-1, Math.min(1, (innerVariance - outerVariance) / 1800));
            // An exterior rim can support a sleeve boundary. A print may have its own white border.
            double outerMean = outside / 20;
            double innerMean = inside / 20;
            frameRim += rimEvidence(outerMean, innerMean, outerVariance);
            if (outside / 20 > 120 && outside > inside) {
                sleeve += Math.min(1, (outside - inside) / 750) * (1 - Math.min(1, outerVariance / 2400));
            }
        }
        return 0.60 * total / 4 + 0.17 * weakest + 0.19 * coherence / 4
                + 0.12 * contrast / 4 + 0.17 * sleeve / 4 + 0.24 * frameRim / 4;
    }

    private static double rimEvidence(double rim, double scene, double variance) {
        return Math.max(0, Math.min(1, (rim - 90) / 65))
                * Math.max(0, Math.min(1, (rim - scene) / 48))
                * Math.max(0, 1 - variance / 800);
    }

    private static int pixel(byte[] values, int width, int height, double x, double y) {
        int px = Math.min(width - 1, Math.max(0, (int) Math.round(x)));
        int py = Math.min(height - 1, Math.max(0, (int) Math.round(y)));
        return Byte.toUnsignedInt(values[py * width + px]);
    }

    private static List<float[]> select(List<Candidate> proposals, int width, int height) {
        proposals.sort(Comparator.comparingDouble(Candidate::score).reversed());
        List<Candidate> distinct = new ArrayList<>();
        for (Candidate candidate : proposals) {
            if (distinct.size() == 60) break;
            if (distinct.stream().noneMatch(other -> duplicate(candidate, other))) distinct.add(candidate);
        }

        List<Candidate> selected = new ArrayList<>();
        for (Candidate candidate : distinct) {


            if (selected.stream().anyMatch(other -> overlap(candidate, other) > 0.45)) continue;
            selected.add(candidate);
            if (selected.size() == MAX_PRINTS) break;
        }
        selected.sort(Comparator.comparingDouble(c -> center(c.corners()).y));
        List<float[]> result = new ArrayList<>();
        for (Candidate candidate : selected) {
            float[] q = new float[8];
            for (int i = 0; i < 4; i++) {
                Point p = candidate.corners()[i];
                q[2 * i] = (float) ((p.x + 0.5) / width);
                q[2 * i + 1] = (float) ((p.y + 0.5) / height);
            }
            if (CropGeometry.valid(q)) result.add(q);
        }
        return result;
    }

    private static boolean duplicate(Candidate a, Candidate b) {
        return Math.min(a.area(), b.area()) > 0.72 * Math.max(a.area(), b.area()) && overlap(a, b) > 0.8;
    }

    private static double overlap(Candidate a, Candidate b) {
        List<Point> clipped = new ArrayList<>(java.util.Arrays.asList(a.corners()));
        for (int i = 0; i < 4 && !clipped.isEmpty(); i++) {
            Point start = b.corners()[i];
            Point end = b.corners()[(i + 1) % 4];
            List<Point> next = new ArrayList<>();
            Point previous = clipped.get(clipped.size() - 1);
            double prevSide = cross(start, end, previous);
            for (Point current : clipped) {
                double side = cross(start, end, current);
                if ((prevSide >= 0) != (side >= 0)) {
                    double t = prevSide / (prevSide - side);
                    next.add(new Point(previous.x + t * (current.x - previous.x),
                            previous.y + t * (current.y - previous.y)));
                }
                if (side >= 0) next.add(current);
                previous = current;
                prevSide = side;
            }
            clipped = next;
        }
        return polygonArea(clipped.toArray(new Point[0])) / Math.min(a.area(), b.area());
    }

    private static double polygonArea(Point[] polygon) {
        double sum = 0;
        for (int i = 0; i < polygon.length; i++) {
            Point a = polygon[i];
            Point b = polygon[(i + 1) % polygon.length];
            sum += a.x * b.y - b.x * a.y;
        }
        return Math.abs(sum) / 2;
    }

    private static double cross(Point a, Point b, Point c) {
        return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
    }

    private static double distance(Point a, Point b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    private static Point center(Point[] points) {
        return new Point((points[0].x + points[1].x + points[2].x + points[3].x) / 4,
                (points[0].y + points[1].y + points[2].y + points[3].y) / 4);
    }
}
















