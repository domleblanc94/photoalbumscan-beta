package local.photoscan;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class L1GeometryFallbackTest {
    private static double[][] quad(double x0,double y0,double x1,double y1){return new double[][]{{x0,y0},{x1,y0},{x1,y1},{x0,y1}};}
    private static L1GeometryFallback.Helper helper(int active,boolean empty,boolean actualNull){return new L1GeometryFallback.Helper(true,actualNull,"inconclusive","weak_side","missing_quad",false,null,true,true,empty,active);}
    private static L1GeometryFallback.Input input(float[] old,L1GeometryFallback.Helper helper,boolean primary){return new L1GeometryFallback.Input("global:0",old,"weak_side",true,quad(10,10,105,105),new double[]{60,60},primary,helper);}
    @Test public void fixedGuardContainsOriginalAndPreservesFallbackIdentity(){
        float[] old=L1GeometryFallback.normalized(quad(20,20,100,100),200,200);
        var accepted=L1GeometryFallback.propose(input(old,helper(1,false,true),true),200,200);
        assertTrue(accepted.accepted());assertTrue(accepted.minimumDistance()>=-.001);assertTrue(accepted.areaRatio()<=1.5);
        for(var h:List.of(helper(0,true,true),helper(1,false,false))){var rejected=L1GeometryFallback.propose(input(old,h,true),200,200);assertFalse(rejected.accepted());assertSame(old,rejected.selected());}
        var fallback=L1GeometryFallback.propose(input(old,helper(1,false,true),false),200,200);assertFalse(fallback.accepted());assertSame(old,fallback.selected());
    }
    @Test public void allPagePeerConflictReturnsBothOriginalObjects(){
        float[] old=L1GeometryFallback.normalized(quad(20,20,100,100),200,200),peer=L1GeometryFallback.normalized(quad(69,20,149,100),200,200);
        var in=new L1GeometryFallback.Input("global:0",old,"weak_side",true,quad(15,15,110,110),new double[]{60,60},true,helper(1,false,true));
        var correction=L1GeometryFallback.propose(in,200,200);assertTrue(correction.accepted());
        var page=L1GeometryFallback.applyPage(List.of(new L1GeometryFallback.Item("global:0",old),new L1GeometryFallback.Item("global:1",peer)),Map.of("global:0",correction),200,200);
        assertTrue(page.reverted());assertSame(old,page.corners().get("global:0"));assertSame(peer,page.corners().get("global:1"));
    }
    @Test public void finalOutlineMaskEvidenceIsRecomputedAndEmptyIsUnknown(){
        float[] mask=new float[100],edge=new float[100];Arrays.fill(edge,1);
        for(int y=2;y<=7;y++)for(int x=2;x<=7;x++)mask[y*10+x]=1;
        double[] transform={1,0,0,0,1,0};double[][] old=quad(7.5,7.5,31.5,31.5),expanded=quad(3.5,3.5,35.5,35.5);
        var before=PictureQuadFit.reviewAtSourceQuad(mask,edge,10,10,transform,old);
        var after=PictureQuadFit.reviewAtSourceQuad(mask,edge,10,10,transform,expanded);
        assertNotNull(before);assertNotNull(after);assertEquals(0,before.omitted(),0);assertEquals(0,before.expansion(),0);
        assertEquals(0,after.omitted(),0);assertEquals(28.0/36,after.expansion(),1e-12);assertEquals(1,after.aspect(),0);
        assertNull(PictureQuadFit.reviewAtSourceQuad(new float[100],edge,10,10,transform,expanded));
    }
}
