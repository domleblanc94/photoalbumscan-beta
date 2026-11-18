package local.photoscan;

import android.util.Size;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import local.photoscan.PictureQuadFit.Vertex;

/** Frozen V3 peak, instance assignment and source geometry. */
final class PicturePageDecode {
    static final int MAX_INSTANCES = 24;
    record Peak(int index, float confidence, int plateau) {}
    record Proposal(Vertex[] quad, float centerX, float centerY, String reason) {}
    record Page(List<Proposal> proposals, boolean overflow) {}

    private PicturePageDecode() {}

    static float probability(float logit) {
        float exponential = (float) Math.exp(-Math.max(-40f, Math.min(logit, 40f)));
        return 1f / (1f + exponential);
    }

    static List<Peak> peaks(float[] centers, int n) {
        boolean[] maxima = new boolean[centers.length];
        for (int i = 0; i < centers.length; i++) {
            float value = centers[i];
            if (!(value >= .28f)) continue;
            boolean maximum = true;
            for (int y = Math.max(0, i / n - 1); y <= Math.min(n - 1, i / n + 1); y++) {
                for (int x = Math.max(0, i % n - 1); x <= Math.min(n - 1, i % n + 1); x++) {
                    if (centers[y * n + x] > value) maximum = false;
                }
            }
            maxima[i] = maximum;
        }
        List<Peak> peaks = new ArrayList<>();
        int[] queue = new int[maxima.length];
        for (int i = 0; i < centers.length; i++) {
            if (!maxima[i]) continue;
            maxima[i] = false;
            queue[0] = i;
            int count = 1, best = i;
            for (int next = 0; next < count; next++) {
                int at = queue[next];
                if (centers[at] > centers[best] || (centers[at] == centers[best] && at < best)) best = at;
                for (int y = Math.max(0, at / n - 1); y <= Math.min(n - 1, at / n + 1); y++) {
                    for (int x = Math.max(0, at % n - 1); x <= Math.min(n - 1, at % n + 1); x++) {
                        int neighbor = y * n + x;
                        if (maxima[neighbor]) {
                            maxima[neighbor] = false;
                            queue[count++] = neighbor;
                        }
                    }
                }
            }
            peaks.add(new Peak(best, centers[best], count));
        }
        peaks.sort(Comparator.comparingDouble(Peak::confidence).reversed().thenComparingInt(Peak::index));
        return peaks;
    }

    static Page decode(float[] mask, float[] boundary, float[] centers, float[] vectors,
                       double[] transform, Size source, int n) {
        List<Peak> all = peaks(centers, n);
        List<Peak> peaks = all.subList(0, Math.min(all.size(), MAX_INSTANCES));
        List<Proposal> proposals = new ArrayList<>();
        if (peaks.isEmpty()) return new Page(proposals, false);
        int[] owners = new int[n * n];
        for (int i = 0; i < owners.length; i++) {
            double endX = i % n + (double) (vectors[i] * n), endY = i / n + (double) (vectors[i + owners.length] * n);
            double closest = Double.POSITIVE_INFINITY;
            for (int p = 0; p < peaks.size(); p++) {
                int at = peaks.get(p).index();
                double dx = endX - at % n, dy = endY - at / n;
                double distance = dx * dx + dy * dy;
                if (distance < closest) {
                    closest = distance;
                    owners[i] = p;
                }
            }
        }
        float[] instance = new float[owners.length];
        for (int p = 0; p < peaks.size(); p++) {
            Arrays.fill(instance, 0f);
            for (int i = 0; i < owners.length; i++) if (owners[i] == p) instance[i] = mask[i];
            var fit = PictureQuadFit.fit(instance, boundary, n, n);
            Vertex[] quad = sourceQuad(fit, transform, source);
            Peak peak = peaks.get(p);
            float cx = (float) ((4. * (peak.index() % n) + 1.5 - transform[2]) / transform[0]);
            float cy = (float) ((4. * (peak.index() / n) + 1.5 - transform[5]) / transform[4]);
            String reason = peak.plateau() > 1 ? "ambiguous_center_plateau" : fitReason(fit, quad);
            proposals.add(new Proposal(quad, cx, cy, reason));
        }
        boolean[] overlap = overlaps(proposals.stream().map(Proposal::quad).collect(Collectors.toList()));
        for (int i = 0; i < overlap.length; i++) if (overlap[i]) {
            Proposal p = proposals.get(i);
            proposals.set(i, new Proposal(p.quad(), p.centerX(), p.centerY(), "duplicate_or_merged_geometry"));
        }
        return new Page(proposals, all.size() > MAX_INSTANCES);
    }

    static Vertex[] sourceQuad(PictureQuadFit.Result fit, double[] transform, Size size) {
        Vertex[] q = fit.quad() == null ? null : PictureQuadFit.toSource(fit.quad(), transform);
        return PictureQuadFit.validSourceQuad(q, size.getWidth(), size.getHeight()) ? q : null;
    }

    static String fitReason(PictureQuadFit.Result fit, Vertex[] quad) {
        if (quad == null) return fit.quad() == null ? "missing_quad" : "invalid_source_quad";
        return fit.reviewRequired() ? fit.reasons().get(0) : "";
    }

    static float[] canvasCorners(Vertex[] source, Size size) {
        if (source == null) return null;
        float[] corners = new float[8];
        for (int i = 0; i < 4; i++) {
            corners[2 * i] = (source[i].x() + .5f) / size.getWidth();
            corners[2 * i + 1] = (source[i].y() + .5f) / size.getHeight();
        }
        return corners;
    }

    static boolean[] overlaps(List<Vertex[]> quads) {
        boolean[] overlap = new boolean[quads.size()];
        for (int i = 0; i < quads.size(); i++) {
            if (quads.get(i) == null) continue;
            for (int j = 0; j < i; j++) {
                if (quads.get(j) != null && iou(quads.get(i), quads.get(j)) > .5) overlap[i] = overlap[j] = true;
            }
        }
        return overlap;
    }

    private record Point(double x, double y) {}

    // Convex clipper handles nesting as well as intersecting boundaries.
    static double iou(Vertex[] a, Vertex[] b) {
        List<Point> clipped = points(a);
        List<Point> clip = points(b);
        for (int i = 0; i < clip.size() && !clipped.isEmpty(); i++) {
            Point left = clip.get(i), right = clip.get((i + 1) % clip.size());
            List<Point> next = new ArrayList<>();
            Point previous = clipped.get(clipped.size() - 1);
            double before = side(left, right, previous);
            for (Point point : clipped) {
                double after = side(left, right, point);
                if ((after >= 0) != (before >= 0)) {
                    double t = before / (before - after);
                    next.add(new Point(previous.x() + t * (point.x() - previous.x()),
                            previous.y() + t * (point.y() - previous.y())));
                }
                if (after >= 0) next.add(point);
                previous = point;
                before = after;
            }
            clipped = next;
        }
        double shared = area(clipped);
        return shared / Math.max(1e-6, area(points(a)) + area(clip) - shared);
    }

    private static List<Point> points(Vertex[] v) {
        return Arrays.stream(v).map(p -> new Point(p.x(), p.y())).collect(Collectors.toList());
    }

    private static double side(Point a, Point b, Point p) {
        return (b.x() - a.x()) * (p.y() - a.y()) - (b.y() - a.y()) * (p.x() - a.x());
    }

    private static double area(List<Point> points) {
        double area = 0;
        for (int i = 0; i < points.size(); i++) {
            Point a = points.get(i), b = points.get((i + 1) % points.size());
            area += a.x() * b.y() - a.y() * b.x();
        }
        return Math.abs(area) / 2;
    }
}



