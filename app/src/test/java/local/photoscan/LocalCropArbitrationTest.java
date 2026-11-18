package local.photoscan;

import static org.junit.Assert.*;
import java.util.List;
import local.photoscan.PictureQuadFit.Vertex;
import org.junit.Test;

public class LocalCropArbitrationTest {
    @Test public void reviewedMergedParentRequiresTwoSurvivingSupportedDistinctChildren() {
        var items=List.of(item("parent",rect(0,0,100,100),true,true,true),
                item("left",rect(0,0,40,100),false,true,true),
                item("right",rect(60,0,100,100),false,true,true));
        var decisions=LocalCropArbitration.resolve(items,0);
        assertEquals("suppressed_parent",decisions.get(0).disposition());
        assertEquals(List.of(1,2),decisions.get(0).children());
        assertEquals("crop",decisions.get(1).disposition());
        assertEquals("crop",decisions.get(2).disposition());
        var protectedItems=List.of(item("parent",rect(0,0,100,100),true,true,false),items.get(1),items.get(2));
        var protectedResult=LocalCropArbitration.resolve(protectedItems,0);
        assertEquals("crop",protectedResult.get(0).disposition());
        assertEquals("deduplicated_local",protectedResult.get(1).disposition());
        assertEquals("deduplicated_local",protectedResult.get(2).disposition());
    }

    @Test public void correctMiddleParentStaysAndDistinctOutsideSiblingCanAppend() {
        var items=List.of(item("middle",rect(40,40,80,80),true,true,true),
                item("duplicate",rect(41,41,79,79),false,true,true),
                item("sibling",rect(0,0,30,30),false,true,true));
        var decisions=LocalCropArbitration.resolve(items,0);
        assertEquals("crop",decisions.get(0).disposition());
        assertEquals("deduplicated_local",decisions.get(1).disposition());
        assertEquals("crop",decisions.get(2).disposition());
    }

    @Test public void reviewedPoint49FallbackIsProtectedEvenWithTwoSupportedChildren() {
        var raw=rect(0,0,100,100);
        var individual=rect(0,0,49,100);
        boolean protectedParent=LocalPictureLocator.undercovered(individual,raw,true,true);
        assertTrue(protectedParent);
        assertFalse(LocalPictureLocator.undercovered(rect(0,0,50,100),raw,true,true));
        var items=List.of(item("individual",individual,true,true,!protectedParent),
                item("left",rect(0,0,20,100),false,true,true),
                item("right",rect(29,0,49,100),false,true,true));
        var decisions=LocalCropArbitration.resolve(items,0);
        assertEquals("crop",decisions.get(0).disposition());
        assertTrue(decisions.get(0).children().isEmpty());
        assertEquals("deduplicated_local",decisions.get(1).disposition());
        assertEquals("deduplicated_local",decisions.get(2).disposition());
    }

    @Test public void verifierHelperNeverBecomesSelectedPrimaryOrMakesItSuppressible() throws Exception {
        var original=rect(0,0,100,100);
        var helperQuad=rect(0,0,20,20);
        var first=new PictureContentDetector.Refined(original,"");
        var helper=new PictureContentDetector.Refined(helperQuad,"weak_side");
        var attempts=new PictureContentDetector.FallbackAttempts();
        var actualFallback=attempts.runIfNeeded(first,original,
                ()->{throw new AssertionError("Clean primary reached fallback branch");});
        var verification=attempts.run(()->helper);
        assertSame(helper,verification);
        var selected=PictureContentDetector.selectedForLocal(first,actualFallback,original,false);
        assertSame(first,selected.refined());
        assertSame(original,selected.refined().quad());
        assertFalse(selected.acceptedFallback());
        assertFalse(selected.reviewedBeforeVerifier());
        assertFalse(LocalPictureLocator.undercovered(selected.refined().quad(),original,
                selected.acceptedFallback(),true));
        var children=List.of(item("primary",original,true,true,selected.reviewedBeforeVerifier()),
                item("left",rect(0,0,40,100),false,true,true),
                item("right",rect(60,0,100,100),false,true,true));
        var decisions=LocalCropArbitration.resolve(children,0);
        assertEquals("crop",decisions.get(0).disposition());
        assertEquals("deduplicated_local",decisions.get(1).disposition());
        assertEquals("deduplicated_local",decisions.get(2).disposition());
    }

    @Test public void nestedLocalFragmentLosesToLargerPhotoAndOriginalCropsWinDuplicates() {
        var items=List.of(item("parent",rect(200,0,300,100),true,false,true),
                item("original",rect(0,0,100,100),true,true,false),
                item("localDuplicate",rect(0,0,90,100),false,true,true),
                item("largerLocal",rect(400,0,500,100),false,true,true),
                item("fragment",rect(410,10,440,40),false,true,true));
        var decisions=LocalCropArbitration.resolve(items,0);
        assertEquals("crop",decisions.get(1).disposition());
        assertEquals("deduplicated_local",decisions.get(2).disposition());
        assertEquals("crop",decisions.get(3).disposition());
        assertEquals("deduplicated_local",decisions.get(4).disposition());
    }

    @Test public void duplicateOrUnsupportedOrZeroAreaChildrenCannotProveSplit() {
        var parent=item("parent",rect(0,0,100,100),true,true,true);
        for(var invalid:List.of(item("duplicate",rect(1,1,39,99),false,true,true),
                item("unsupported",rect(60,0,100,100),false,false,true),
                item("zero",rect(60,0,60,100),false,true,true))) {
            var decisions=LocalCropArbitration.resolve(List.of(parent,
                    item("child",rect(0,0,40,100),false,true,true),invalid),0);
            assertEquals("crop",decisions.get(0).disposition());
            assertTrue(decisions.get(0).children().isEmpty());
        }
    }

    @Test public void giantLocalMergeCannotBeatTwoDisjointCropsAndProofReferencesSurvive() {
        var items=List.of(item("parent",rect(0,0,100,100),true,true,true),
                item("localMerge",rect(0,0,100,100),false,true,true),
                item("left",rect(0,0,40,100),false,true,true),
                item("right",rect(60,0,100,100),false,true,true),
                item("leftFragment",rect(2,10,20,50),false,true,true));
        var decisions=LocalCropArbitration.resolve(items,0);
        for(int i=0;i<decisions.size();i++) {
            if(!decisions.get(i).disposition().equals("suppressed_parent"))continue;
            assertTrue(decisions.get(i).children().size()>=2);
            for(int child:decisions.get(i).children()) {
                assertEquals("crop",decisions.get(child).disposition());
                assertTrue(LocalPictureLocator.area(items.get(child).quad())*2<=LocalPictureLocator.area(items.get(i).quad()));
            }
        }
        assertEquals("deduplicated_local",decisions.get(4).disposition());
        assertEquals("crop",decisions.get(2).disposition());
        assertEquals("crop",decisions.get(3).disposition());
    }

    private static LocalCropArbitration.Item item(String id,Vertex[] quad,boolean original,boolean supported,boolean reviewed) {
        return new LocalCropArbitration.Item(id,quad,original,supported,reviewed);
    }
    private static Vertex[] rect(float left,float top,float right,float bottom) {
        return new Vertex[]{new Vertex(left,top),new Vertex(right,top),new Vertex(right,bottom),new Vertex(left,bottom)};
    }
}
