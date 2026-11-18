package local.photoscan;

import org.junit.Test;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class LocalCropArbitrationRegressionTest {
    @Test public void majorityRejectsFalseFragmentSplit() {
        var decisions = LocalCropArbitration.resolve(List.of(
                original(false), local("a", 0, 0, 30, 60), local("b", 50, 0, 80, 60)), 0);
        assertEquals("crop", decisions.get(0).disposition());
        assertEquals("deduplicated_local", decisions.get(1).disposition());
        assertEquals("deduplicated_local", decisions.get(2).disposition());
    }

    @Test public void supportedMajorityAllowsRealSplit() {
        var decisions = LocalCropArbitration.resolve(List.of(
                original(false), local("a", 0, 0, 40, 80), local("b", 60, 0, 100, 80)), 0);
        assertEquals("suppressed_parent", decisions.get(0).disposition());
        assertEquals(List.of(1, 2), decisions.get(0).children());
        assertEquals("crop", decisions.get(1).disposition());
        assertEquals("crop", decisions.get(2).disposition());
    }

    @Test public void singleSupportedChildSalvagedOnlyWhenExplicitlyEligible() {
        var child = local("a", 0, 0, 40, 80);
        var salvage = LocalCropArbitration.resolve(List.of(original(true), child), 0);
        assertEquals("unsupported_parent_unresolved", salvage.get(0).disposition());
        assertEquals(List.of(1), salvage.get(0).children());
        assertEquals("crop", salvage.get(1).disposition());
        var protectedParent = LocalCropArbitration.resolve(List.of(original(false), child), 0);
        assertEquals("crop", protectedParent.get(0).disposition());
        assertEquals("deduplicated_local", protectedParent.get(1).disposition());
    }

    private static LocalCropArbitration.Item original(boolean eligible) {
        return new LocalCropArbitration.Item("parent", quad(0, 0, 100, 100), true, false, true, eligible);
    }

    private static LocalCropArbitration.Item local(String id, float l, float t, float r, float b) {
        return new LocalCropArbitration.Item(id, quad(l, t, r, b), false, true, true);
    }

    private static PictureQuadFit.Vertex[] quad(float l, float t, float r, float b) {
        return new PictureQuadFit.Vertex[]{new PictureQuadFit.Vertex(l,t), new PictureQuadFit.Vertex(r,t),
                new PictureQuadFit.Vertex(r,b), new PictureQuadFit.Vertex(l,b)};
    }
}

