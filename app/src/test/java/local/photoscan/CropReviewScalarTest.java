package local.photoscan;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class CropReviewScalarTest {
    private static final float[] RETAINED = {.25f, .25f, .75f, .25f, .75f, .75f, .25f, .75f};
    private static final double[] EXACT_PARENT = {.25, .25, .75, .25, .75, .75, .25, .75};

    @Test public void cleanExactParentWithNoPeersStaysQuiet() {
        var crop = candidate(RETAINED, new CropReviewPolicy.FitEvidence(0, 0, false, 1));
        var score = CropReviewScalar.score(crop, List.of(crop), EXACT_PARENT, 1000, 800);
        assertNotNull(score);
        assertArrayEquals(new double[9], score.features(), 0);
        assertFalse(score.warns());
    }

    @Test public void usesExistingParentAndRetainedPeersInFrozenFeatureOrder() {
        var fit = new CropReviewPolicy.FitEvidence(.25, .25, true, 1);
        var crop = candidate(RETAINED, fit);
        var peer = candidate(new float[]{.5f, .25f, 1, .25f, 1, .75f, .5f, .75f}, fit);
        double[] parent = {0, 0, 1, 0, 1, 1, 0, 1};
        var score = CropReviewScalar.score(crop, List.of(crop, peer), parent, 1000, 800);
        assertNotNull(score);
        assertArrayEquals(new double[]{.75, 0, .25, 1. / 3, 0, 0, .25, .2, 1}, score.features(), 1e-12);
        assertTrue(score.warns());
        assertEquals(CropReviewPolicy.POSSIBLE_PICTURE_CUT,
                CropReviewPolicy.legacyReason(CropReviewPolicy.POSSIBLE_PICTURE_CUT));
    }

    @Test public void abstainsOnUnknownOrInvalidEvidence() {
        var crop = candidate(RETAINED, new CropReviewPolicy.FitEvidence(0, 0, false, 1));
        assertNull(CropReviewScalar.score(crop, List.of(crop), null, 1000, 800));
        double[] invalid = EXACT_PARENT.clone();
        invalid[0] = Double.NaN;
        assertNull(CropReviewScalar.score(crop, List.of(crop), invalid, 1000, 800));
        var unknown = candidate(RETAINED, null);
        assertNull(CropReviewScalar.score(unknown, List.of(unknown), EXACT_PARENT, 1000, 800));
        var badFit = candidate(RETAINED, new CropReviewPolicy.FitEvidence(0, -.01, false, 1));
        assertNull(CropReviewScalar.score(badFit, List.of(badFit), EXACT_PARENT, 1000, 800));
    }

    private static PictureContentDetector.Candidate candidate(float[] corners, CropReviewPolicy.FitEvidence fit) {
        return new PictureContentDetector.Candidate(corners, "", null, "crop", null, null, fit);
    }
}
