package local.photoscan;

import java.util.List;

/** Advisory warnings based on the retained crop, independent of detection and page completeness. */
final class CropReviewPolicy {
    static final String CONTENT_CUT = "material_picture_cut";
    static final String MISPLACED = "possible_misplaced_crop";
    static final String OVERLAP = "duplicate_or_merged_geometry";
    static final String SKEW = "isolated_crop_skew";
    static final String BOUNDARY = "material_boundary_disagreement";
    static final String POSSIBLE_PICTURE_CUT = "possible_picture_cut";
    private static final double[] PRINT_RATIOS = {1, 5.0 / 4, 4.0 / 3, 7.0 / 5, 3.0 / 2};

    record FitEvidence(double omitted, double expansion, boolean weakEdge, double aspect) {}

    private CropReviewPolicy() {}

    static String reason(PictureContentDetector.Candidate crop,
                         List<PictureContentDetector.Candidate> retained) {
        return reason(crop, retained, 0, 0, null);
    }

    static String reason(PictureContentDetector.Candidate crop,
                         List<PictureContentDetector.Candidate> retained,
                         int width, int height, CropReviewImageEvidence.Alternate alternate) {
        double area = CropGeometry.area(crop.corners);
        for (var other : retained) {
            if (crop == other) continue;
            double iou = CropGeometry.iou(crop.corners, other.corners);
            if (iou >= .5) return OVERLAP;
            double otherArea = CropGeometry.area(other.corners);
            double intersection = iou * (area + otherArea) / (1 + iou);
            double larger = Math.max(area, otherArea);
            double smaller = Math.min(area, otherArea);
            // Comparable crops sharing at least half of both outlines are ambiguous
            // even when each has a different overhang and union IoU falls below .5.
            if (larger < 1.5 * smaller && intersection >= .50 * larger) return OVERLAP;
            // A larger crop covering much of another print may be merged. Keep the
            // contained crop quiet when its own evidence supports it.
            if (area >= 1.5 * otherArea && intersection >= .75 * otherArea
                    && intersection >= .20 * area) return OVERLAP;
        }
        String existing = fitReason(crop.fitEvidence);
        if (!existing.isEmpty() || width <= 0 || height <= 0) return existing;
        double angle = oppositeDivergence(crop.corners, width, height);
        if (angle >= 7) {
            int peers = 0;
            double maximumPeer = 0;
            for (var other : retained) {
                if (other == crop) continue;
                maximumPeer = Math.max(maximumPeer, oppositeDivergence(other.corners, width, height));
                peers++;
            }
            if (peers >= 2 && angle >= maximumPeer + 2) return SKEW;
        }
        FitEvidence fit = crop.fitEvidence;
        if (fit != null && angle >= 4 && fit.expansion() >= .20 && fit.omitted() >= .04)
            return BOUNDARY;
        if (alternate != null && angle >= 5 && alternate.gain() >= 35)
            return BOUNDARY;
        return "";
    }

    static double oppositeDivergence(float[] quad, int width, int height) {
        double maximum = 0;
        for (int i = 0; i < 2; i++) {
            double ax = ((double) quad[(i + 1) * 2] - quad[i * 2]) * width;
            double ay = ((double) quad[(i + 1) * 2 + 1] - quad[i * 2 + 1]) * height;
            int k = i + 2;
            int next = (k + 1) % 4;
            double bx = ((double) quad[next * 2] - quad[k * 2]) * width;
            double by = ((double) quad[next * 2 + 1] - quad[k * 2 + 1]) * height;
            // Undirected line angle. A reversed contour has the same divergence.
            double angle = Math.toDegrees(Math.atan2(Math.abs(ax * by - ay * bx),
                    Math.abs(ax * bx + ay * by)));
            maximum = Math.max(maximum, angle);
        }
        return maximum;
    }

    static String fitReason(FitEvidence fit) {
        if (fit == null) return "";
        if (fit.omitted() >= .27 || (fit.omitted() >= .18 && fit.weakEdge())) return CONTENT_CUT;
        if (fit.weakEdge() && (fit.expansion() >= .40
                || (fit.expansion() >= .25 && fit.omitted() >= .06)
                || (fit.expansion() >= .32 && unusualPrintRatio(fit.aspect())))) return MISPLACED;
        return "";
    }

    static String legacyReason(String reason) {
        // Older saves only kept a string. Weak fit, recovery, verifier and branch
        // names do not quantify the retained crop and cannot justify a warning.
        return CONTENT_CUT.equals(reason) || MISPLACED.equals(reason) || OVERLAP.equals(reason)
                || SKEW.equals(reason) || BOUNDARY.equals(reason) || POSSIBLE_PICTURE_CUT.equals(reason)
                ? reason : "";
    }

    private static boolean unusualPrintRatio(double aspect) {
        if (!Double.isFinite(aspect)) return false;
        for (double standard : PRINT_RATIOS) {
            if (Math.abs(Math.log(aspect / standard)) <= Math.log(1.20)) return false;
        }
        return true;
    }
}
