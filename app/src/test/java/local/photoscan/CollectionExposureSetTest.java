package local.photoscan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CollectionExposureSetTest {
    @Test public void advancesOnlyAfterDurableCommitAndStopsWithSavedCount() {
        var set = new CollectionExposureSet("sampled_bracket");
        assertTrue(set.hasNext());
        assertEquals("normal", set.nextRole());
        assertEquals(0, set.nextEv());
        set.committed();
        assertEquals("darker", set.nextRole());
        assertEquals(-1, set.nextEv());
        set.stop();
        set.committed(); // A JPEG already in flight may finish after Stop.
        assertEquals(2, set.saved());
        assertFalse(set.hasNext());
        assertNotEquals(set.id, new CollectionExposureSet("forced_bracket").id);
    }

    @Test public void completesAfterNormalDarkerAndBrighter() {
        var set = new CollectionExposureSet("forced_bracket");
        set.committed();
        set.committed();
        assertEquals("brighter", set.nextRole());
        assertEquals(1, set.nextEv());
        set.committed();
        assertEquals(CollectionExposureSet.SIZE, set.saved());
        assertFalse(set.hasNext());
    }

    @Test public void samplesTheFirstAndEveryFourthSceneOfEachBook() {
        assertTrue(CollectionExposureSet.samples(1, false));
        assertTrue(CollectionExposureSet.samples(5, false));
        assertTrue(CollectionExposureSet.samples(9, false));
        assertFalse(CollectionExposureSet.samples(0, false));
        assertFalse(CollectionExposureSet.samples(2, false));
        assertFalse(CollectionExposureSet.samples(4, false));
        assertFalse(CollectionExposureSet.samples(5, true));
    }
}
