package local.photoscan;

import java.util.List;

/** Experimental advisory over an already retained crop and its existing locator proposal. */
final class CropReviewScalar {
    private static final double THRESHOLD = .60;
    private static final double INTERCEPT = -0.4719731934042427;
    private static final double[] MEAN = {
            0.14587749519313278, 0.057812208402523685, 0.05085106273733761,
            0.0191495756117326, 6.864298430159589, 1.561884848619389,
            0.03076278403862083, 0.0409058919103385, 0.25039682539682534
    };
    private static final double[] SCALE = {
            0.1694710511948013, 0.05501319714580459, 0.15836713847867462,
            0.07166868660124905, 7.720175566081225, 4.754143999667722,
            0.027361354468044403, 0.046979795257825654, 0.4332415668285038
    };
    private static final double[] COEFFICIENT = {
            0.5435169886355313, 0.13739248977592297, 0.07670758982995037,
            0.041888325027087066, 0.4425063220999934, 0.10897980411589887,
            0.44172897575760534, 0.309262578603506, 0.40345500081015095
    };

    record Score(double[] features, double probability) {
        boolean warns() {
            return probability >= THRESHOLD;
        }
    }

    private CropReviewScalar() {}

    static Score score(PictureContentDetector.Candidate crop,
                       List<PictureContentDetector.Candidate> retained,
                       double[] parent, int width, int height) {
        var fit = crop.fitEvidence;
        if (!"crop".equals(crop.disposition) || width <= 0 || height <= 0 || fit == null
                || !Double.isFinite(fit.omitted()) || !Double.isFinite(fit.expansion())
                || fit.expansion() < 0 || !valid(parent)) return null;
        double[] quad = asDouble(crop.corners);
        if (!valid(quad)) return null;
        double cropArea = area(quad, quad.length);
        double parentArea = area(parent, parent.length);
        double shared = intersection(quad, parent, cropArea, parentArea);
        double angle = CropReviewPolicy.oppositeDivergence(crop.corners, width, height);
        int peers = 0;
        double maxPeerAngle = 0;
        double maxPeerIou = 0;
        double maxOtherInParent = 0;
        for (var other : retained) {
            if (other == crop || !"crop".equals(other.disposition)) continue;
            double[] peer = asDouble(other.corners);
            if (!valid(peer)) continue;
            peers++;
            double peerArea = area(peer, peer.length);
            double overlap = intersection(quad, peer, cropArea, peerArea);
            maxPeerIou = Math.max(maxPeerIou, overlap / (cropArea + peerArea - overlap));
            maxOtherInParent = Math.max(maxOtherInParent,
                    intersection(peer, parent, peerArea, parentArea) / parentArea);
            maxPeerAngle = Math.max(maxPeerAngle,
                    CropReviewPolicy.oppositeDivergence(other.corners, width, height));
        }
        double[] features = {
                1 - shared / parentArea, 1 - shared / cropArea, maxOtherInParent,
                maxPeerIou, angle, peers >= 2 ? Math.max(0, angle - maxPeerAngle) : 0,
                Math.min(1, Math.max(0, fit.omitted())), fit.expansion() / (1 + fit.expansion()),
                fit.weakEdge() ? 1 : 0
        };
        double weighted = 0;
        for (int i = 0; i < features.length; i++) {
            weighted += COEFFICIENT[i] * ((features[i] - MEAN[i]) / SCALE[i]);
        }
        return new Score(features, 1 / (1 + Math.exp(-(INTERCEPT + weighted))));
    }

    private static double[] asDouble(float[] quad) {
        if (quad == null || quad.length != 8) return null;
        double[] result = new double[8];
        for (int i = 0; i < result.length; i++) result[i] = quad[i];
        return result;
    }

    private static boolean valid(double[] quad) {
        if (quad == null || quad.length != 8) return false;
        for (double value : quad) {
            if (!Double.isFinite(value) || value < 0 || value > 1) return false;
        }
        for (int a = 0; a < 8; a += 2) {
            int b = (a + 2) % 8;
            int c = (a + 4) % 8;
            if (side(quad, a, b, quad[c], quad[c + 1]) < 1e-6) return false;
        }
        return true;
    }

    private static double area(double[] polygon, int count) {
        double forward = 0;
        double backward = 0;
        for (int a = 0; a < count; a += 2) {
            int b = (a + 2) % count;
            forward += polygon[a] * polygon[b + 1];
            backward += polygon[a + 1] * polygon[b];
        }
        return Math.abs(forward - backward) / 2;
    }

    private static double intersection(double[] first, double[] second,
                                       double firstArea, double secondArea) {
        // Protocol v2 uses Float64 Sutherland-Hodgman clipping. Keep it separate
        // from the existing detection and review overlap calculations.
        double[] current = first;
        int count = first.length;
        for (int a = 0; a < 8; a += 2) {
            int b = (a + 2) % 8;
            double[] next = new double[count * 2];
            int used = 0;
            for (int end = 0; end < count; end += 2) {
                int start = (end + count - 2) % count;
                double d0 = side(second, a, b, current[start], current[start + 1]);
                double d1 = side(second, a, b, current[end], current[end + 1]);
                if ((d0 >= 0) != (d1 >= 0)) {
                    double t = d0 / (d0 - d1);
                    next[used++] = current[start] + (current[end] - current[start]) * t;
                    next[used++] = current[start + 1] + (current[end + 1] - current[start + 1]) * t;
                }
                if (d1 >= 0) {
                    next[used++] = current[end];
                    next[used++] = current[end + 1];
                }
            }
            if (used < 6) return 0;
            current = next;
            count = used;
        }
        return Math.min(Math.min(firstArea, secondArea), Math.max(0, area(current, count)));
    }

    private static double side(double[] clip, int a, int b, double x, double y) {
        return (y - clip[a + 1]) * (clip[b] - clip[a])
                - (x - clip[a]) * (clip[b + 1] - clip[a + 1]);
    }
}
