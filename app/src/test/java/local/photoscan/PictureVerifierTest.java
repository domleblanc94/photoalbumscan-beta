package local.photoscan;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class PictureVerifierTest {
    @Test public void onlyStrongFirstPassEmptyEvidenceVetoes() {
        assertTrue(veto(true,0,.05,true));
        assertTrue(veto(true,0,0,true));
        assertTrue(veto(true,0,Math.nextDown(.05f),true));
        assertFalse(veto(true,0,(double).05f,true));
        assertFalse(veto(true,0,Math.nextUp(.05f),true));
        assertFalse(veto(false,0,.001,true));
        assertFalse(veto(true,1,.001,true));
        assertFalse(veto(true,0,.001,false));
        assertFalse(veto(true,0,Double.NaN,true));
        assertFalse(veto(true,0,-.01,true));
        assertFalse(PictureContentDetector.semanticBackground(
                new PictureContentDetector.Refined(null,"own_fallback_unavailable")));
    }

    @Test public void NonnullHelperAndGeometricFailureNeverActAsSemanticNegative() {
        var quad=new PictureQuadFit.Vertex[]{new PictureQuadFit.Vertex(0,0),new PictureQuadFit.Vertex(10,0),
                new PictureQuadFit.Vertex(10,10),new PictureQuadFit.Vertex(0,10)};
        var evidence=new PictureContentDetector.FirstMaskEvidence(true,0,.001,true);
        assertFalse(PictureContentDetector.semanticBackground(new PictureContentDetector.Refined(
                quad,"",0,List.of(),evidence,true)));
        assertFalse(PictureContentDetector.semanticBackground(new PictureContentDetector.Refined(
                null,"missing_quad",0,List.of("weak_side"),new PictureContentDetector.FirstMaskEvidence(false,3,.9,true),false)));
    }

    @Test public void verifiedBackgroundRemainsDiagnosticButIsNotAnExportedOrMissingPhoto() {
        var background=new PictureContentDetector.Candidate(null,"verified_background",null,"verified_background",null);
        assertTrue(background.reviewRequired);
        assertTrue(background.omittedFromPhotos());
        assertEquals("verified_background",background.disposition);
        var unresolved=new PictureContentDetector.Candidate(null,"missing_quad");
        assertFalse(unresolved.omittedFromPhotos());
        assertTrue(unresolved.reviewRequired);
        assertEquals("unresolved",unresolved.disposition);
    }

    @Test public void agreementThresholdIsStrictAndInvalidGeometryHasNoOverlapWarning() {
        var primary=rect(100,100);
        assertEquals(.80,PictureContentDetector.agreement(primary,rect(80,100),101,101),1e-12);
        assertFalse(PictureContentDetector.agreement(primary,rect(80,100),101,101)<.80);
        assertTrue(PictureContentDetector.agreement(primary,rect(Math.nextDown(80f),100),101,101)<.80);
        assertEquals(1.,PictureContentDetector.agreement(primary,primary,101,101),0);
        assertNull(PictureContentDetector.agreement(primary,null,101,101));
        assertNull(PictureContentDetector.agreement(primary,rect(Float.NaN,100),101,101));
        assertNull(PictureContentDetector.agreement(primary,rect(0,100),101,101));
        assertNull(PictureContentDetector.agreement(primary,rect(200,100),101,101));
    }

    private static PictureQuadFit.Vertex[] rect(float width,float height) {
        return new PictureQuadFit.Vertex[]{new PictureQuadFit.Vertex(0,0),new PictureQuadFit.Vertex(width,0),
                new PictureQuadFit.Vertex(width,height),new PictureQuadFit.Vertex(0,height)};
    }

    private static boolean veto(boolean empty,int active,double maximum,boolean finite) {
        return PictureContentDetector.semanticBackground(new PictureContentDetector.Refined(
                null,"missing_quad",0,List.of("empty_mask"),
                new PictureContentDetector.FirstMaskEvidence(empty,active,maximum,finite),false));
    }
}
