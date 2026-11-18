package local.photoscan;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GlareHintTest {
    private final GlareHint hint = new GlareHint();
    private final float[] spot = {.4f, .4f};

    @Test public void requiresNearbyObservationsAndClearsAfterTwoMisses() {
        assertFalse(hint.observe(true, spot, 1000));
        assertFalse(hint.observe(true, new float[]{.7f, .7f}, 1400));
        assertFalse(hint.observe(true, spot, 1800));
        assertTrue(hint.observe(true, new float[]{.42f, .43f}, 2200));
        assertTrue(hint.observe(true, null, 2600));
        assertFalse(hint.observe(true, null, 3000));
    }

    @Test public void disabledAndStaleObservationsDoNotCarryOver() {
        assertFalse(hint.observe(true, spot, 1000));
        assertFalse(hint.observe(true, spot, 2400));
        assertTrue(hint.observe(true, spot, 2800));
        assertFalse(hint.observe(false, spot, 3200));
        assertFalse(hint.observe(false, spot, 3600));
        assertFalse(hint.observe(true, spot, 4000));
        assertTrue(hint.observe(true, spot, 4400));
        hint.clear();
        assertFalse(hint.visible());
    }

    @Test public void hiddenFirstSpotCannotMaskVisibleSpotAndTrackingSurvivesReordering() {
        float[] unrelated = {.8f, .8f};
        float[] moved = {.42f, .43f};
        assertFalse(hint.observeVisible(true, new float[][]{unrelated, spot}, 0b10, 1000));
        assertTrue(hint.observeVisible(true, new float[][]{unrelated, moved}, 0b11, 1400));
        assertTrue(hint.observeVisible(true, new float[][]{moved, unrelated}, 0b01, 1800));
        assertTrue(hint.observeVisible(true, new float[][]{moved, unrelated}, 0, 2200));
        assertFalse(hint.observeVisible(true, new float[][]{moved, unrelated}, 0, 2600));
    }
}
