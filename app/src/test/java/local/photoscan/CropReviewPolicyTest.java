package local.photoscan;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

public class CropReviewPolicyTest {
    @Test public void minorFitDefectsAndOddAspectAreQuiet() {
        assertEquals("", fit(.07, .12, true, 2.4));
        assertEquals("", fit(.01, .02, false, 3.2));
        // Omission near the cut threshold stays quiet when expansion is modest.
        assertEquals("", fit(.17, .12, true, 1.5));
        assertEquals("", fit(.01, .35, false, 2.4));
    }

    @Test public void materialLossAndWeakMisplacedFitsAreFlagged() {
        assertEquals(CropReviewPolicy.CONTENT_CUT, fit(.20, .04, true, 1.5));
        assertEquals(CropReviewPolicy.CONTENT_CUT, fit(.30, .04, false, 1.5));
        assertEquals(CropReviewPolicy.MISPLACED, fit(.02, .42, true, 1.5));
        assertEquals(CropReviewPolicy.MISPLACED, fit(.02, .35, true, 2.4));
        assertEquals(CropReviewPolicy.MISPLACED, fit(.068, .271, true, 1.49));
        assertEquals("", fit(.068, .271, false, 1.49));
        assertEquals("", fit(.059, .271, true, 1.49));
        assertEquals("", fit(.068, .249, true, 1.49));
        assertEquals("", fit(.02, .35, true, 1.5));
    }

    @Test public void finalOverlapOutweighsProvenanceButAdjacentCropsPass() {
        var fallback = crop(.08f, .1f, .35f, .4f, "own_mixed_fallback");
        var local = crop(.34f, .1f, .62f, .4f, "local_crop_review");
        var duplicate = crop(.10f, .12f, .36f, .42f, "");
        assertEquals("", CropReviewPolicy.reason(fallback, List.of(fallback, local)));
        assertEquals("", CropReviewPolicy.reason(local, List.of(fallback, local)));
        assertEquals(CropReviewPolicy.OVERLAP, CropReviewPolicy.reason(duplicate, List.of(fallback, duplicate)));
        assertEquals(CropReviewPolicy.OVERLAP, CropReviewPolicy.reason(fallback, List.of(fallback, duplicate)));
    }

    @Test public void substantialNestingFlagsLargerCropOnly() {
        var large = crop(.1f, .1f, .8f, .5f, "");
        var small = crop(.48f, .14f, .75f, .43f, "");
        assertEquals(CropReviewPolicy.OVERLAP, CropReviewPolicy.reason(large, List.of(large, small)));
        assertEquals("", CropReviewPolicy.reason(small, List.of(large, small)));
    }

    @Test public void comparableCrossingCropsWithMajorSharedAreaFlagBoth() {
        // Approximate hand trace from the displayed phone screenshot. Coordinates
        // are normalized to the preview rectangle, not raw source annotations.
        var separate = screenshotCrop(207, 598, 632, 578, 738, 885, 213, 900);
        var wide = screenshotCrop(221, 997, 672, 982, 678, 1203, 224, 1151);
        var tall = screenshotCrop(220, 998, 467, 991, 530, 1208, 224, 1262);
        var retained = List.of(separate, wide, tall);
        org.junit.Assert.assertTrue(CropGeometry.iou(wide.corners, tall.corners) < .5);
        assertEquals("", CropReviewPolicy.reason(separate, retained));
        assertEquals(CropReviewPolicy.OVERLAP, CropReviewPolicy.reason(wide, retained));
        assertEquals(CropReviewPolicy.OVERLAP, CropReviewPolicy.reason(tall, retained));
        assertEquals(CropReviewPolicy.OVERLAP,
                CropReviewPolicy.legacyReason(CropReviewPolicy.reason(tall, retained)));
    }

    @Test public void comparableCropsSharingLessThanHalfStayQuiet() {
        var left = crop(.1f, .1f, .5f, .5f, "");
        var right = crop(.31f, .1f, .71f, .5f, "");
        assertEquals("", CropReviewPolicy.reason(left, List.of(left, right)));
        assertEquals("", CropReviewPolicy.reason(right, List.of(left, right)));
    }

    @Test public void retainedFitCanFlagAnOtherwiseOrdinaryCrop() {
        var base = crop(.1f, .1f, .4f, .6f, "");
        var crop = new PictureContentDetector.Candidate(base.corners, "", null, "crop", null, null,
                new CropReviewPolicy.FitEvidence(.25, .04, true, 1.5));
        assertEquals(CropReviewPolicy.CONTENT_CUT, CropReviewPolicy.reason(crop, List.of(crop)));
    }

    @Test public void legacyBadgesKeepOnlyMaterialReasons() {
        for (String diagnostic : List.of("own_mixed_fallback", "local_crop_review", "weak_side",
                "source_edge_context_retry", "poor_mask_agreement", "own_verifier_uncertain")) {
            assertEquals("", CropReviewPolicy.legacyReason(diagnostic));
        }
        assertEquals("", CropReviewPolicy.legacyReason(null));
        assertEquals(CropReviewPolicy.OVERLAP, CropReviewPolicy.legacyReason(CropReviewPolicy.OVERLAP));
        assertEquals(CropReviewPolicy.CONTENT_CUT, CropReviewPolicy.legacyReason(CropReviewPolicy.CONTENT_CUT));
        assertEquals(CropReviewPolicy.MISPLACED, CropReviewPolicy.legacyReason(CropReviewPolicy.MISPLACED));
        assertEquals(CropReviewPolicy.SKEW, CropReviewPolicy.legacyReason(CropReviewPolicy.SKEW));
        assertEquals(CropReviewPolicy.BOUNDARY, CropReviewPolicy.legacyReason(CropReviewPolicy.BOUNDARY));
    }

    @Test public void isolatedSkewUsesUprightPixelGeometryAndTwoPeers() {
        var skew = slanted(.05f, .02f);
        var peer1 = crop(.40f, .10f, .65f, .55f, "");
        var peer2 = crop(.70f, .10f, .95f, .55f, "");
        var all = List.of(skew, peer1, peer2);
        assertEquals(CropReviewPolicy.SKEW, CropReviewPolicy.reason(skew, all, 3000, 4000, null));
        assertEquals("", CropReviewPolicy.reason(skew, all, 4000, 3000, null));
        assertEquals("", CropReviewPolicy.reason(skew, List.of(skew, peer1), 3000, 4000, null));
    }

    @Test public void similarlyProjectedNeighborsStayQuiet() {
        var left = slanted(.03f, .05f);
        var middle = slanted(.37f, .055f);
        var right = slanted(.70f, .055f);
        var all = List.of(left, middle, right);
        for (var item : all) {
            assertEquals("", CropReviewPolicy.reason(item, all, 3000, 4000, null));
        }
    }

    @Test public void alternateBoundaryRequiresCorroboratingSkew() {
        var skew = slanted(.05f, .02f);
        var modest = slanted(.40f, .01f);
        var justEnough = new CropReviewImageEvidence.Alternate(35, .06);
        var substantial = new CropReviewImageEvidence.Alternate(70, .16);
        assertEquals(CropReviewPolicy.BOUNDARY,
                CropReviewPolicy.reason(skew, List.of(skew), 3000, 4000, justEnough));
        assertEquals("", CropReviewPolicy.reason(modest, List.of(modest), 3000, 4000, justEnough));
        assertEquals("",
                CropReviewPolicy.reason(modest, List.of(modest), 3000, 4000, substantial));
        assertEquals("", CropReviewPolicy.reason(skew, List.of(skew), 3000, 4000,
                new CropReviewImageEvidence.Alternate(Math.nextDown(35.0), .20)));
    }

    @Test public void modestAngleCanCorroborateCombinedMaskDiscrepancy() {
        var skew = slanted(.05f, .02f);
        var item = new PictureContentDetector.Candidate(skew.corners, "", null, "crop", null, null,
                new CropReviewPolicy.FitEvidence(.04, .20, false, 1.5));
        assertEquals(CropReviewPolicy.BOUNDARY,
                CropReviewPolicy.reason(item, List.of(item), 3000, 4000, null));
        assertEquals("", CropReviewPolicy.reason(item, List.of(item), 5000, 3000, null));
    }

    private static PictureContentDetector.Candidate slanted(float x, float bottomRise) {
        return new PictureContentDetector.Candidate(
                new float[]{x, .1f, x + .30f, .1f, x + .30f, .5f, x, .5f - bottomRise * 1.5f}, "");
    }

    private static String fit(double omitted, double expansion, boolean weakEdge, double aspect) {
        return CropReviewPolicy.fitReason(new CropReviewPolicy.FitEvidence(omitted, expansion, weakEdge, aspect));
    }

    private static PictureContentDetector.Candidate crop(float left, float top, float right, float bottom, String reason) {
        return new PictureContentDetector.Candidate(new float[]{left, top, right, top, right, bottom, left, bottom}, reason);
    }

    private static PictureContentDetector.Candidate screenshotCrop(float... corners) {
        for (int i = 0; i < corners.length; i += 2) {
            corners[i] = (corners[i] - 123) / 675;
            corners[i + 1] = (corners[i + 1] - 424) / 897;
        }
        return new PictureContentDetector.Candidate(corners, "");
    }
}
