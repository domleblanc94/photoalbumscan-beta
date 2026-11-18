package local.photoscan;

import android.util.Size;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import local.photoscan.PictureQuadFit.Vertex;

/** Optional frozen C1 experiment. Original model returns are never modified. */
final class InstanceBoundaryIntegration {
    static final String POLICY="shipped-c1-v1";
    record Captured(InstanceBoundaryMath.View view,float[] edge,double[] proposal,double[] cue,
                    String stage,boolean retry,PictureContentDetector.Refined returned) {}
    record Provenance(String kind,String role,String branch,String callId,Captured captured,PictureContentDetector.Refined returned) {}
    record Effective(PictureContentDetector.SelectedBranch branch,boolean corroborated,
                     InstanceBoundaryMath.Choice choice,Provenance provenance,float[] originalCorners,
                     float[] chosenCorners,String scope) {}
    record Timing(long captureNanos,long evidenceNanos,long generationNanos,long featureNanos,long scoringNanos,long applyNanos,
                  int capturedViews,int generatedPools,int generatedCandidates) {}
    private long captureNanos,evidenceNanos,generationNanos,featureNanos,scoringNanos,applyNanos;
    private int capturedViews,generatedPools,generatedCandidates;
    Timing timing(){return new Timing(captureNanos,evidenceNanos,generationNanos,featureNanos,scoringNanos,applyNanos,capturedViews,generatedPools,generatedCandidates);}
    void resetPage(){clear();captureNanos=evidenceNanos=generationNanos=featureNanos=scoringNanos=applyNanos=0;capturedViews=generatedPools=generatedCandidates=0;}
    private final List<Captured> captured=new ArrayList<>();
    private final InstanceBoundaryOpenCv backend=new InstanceBoundaryOpenCv();
    void clear(){captured.clear();}
    void capture(PictureContentDetector.RefinerCall call,PicturePageDecode.Proposal proposal,
                 FloatBuffer packed,float[] mask,float[] edge,PictureContentDetector.Refined returned){
        if(!call.stage().startsWith("global_"))return;
        long captureStarted=System.nanoTime();
        try {
        long count=captured.stream().filter(c->c.view.id.startsWith(call.lineage()+"/")).count();
        if(count>=InstanceBoundaryMath.MAX_VIEWS)throw new IllegalStateException("C1 global view ceiling");
        String id=call.lineage()+"/call:"+count;
        long evidenceStarted=System.nanoTime();float[] evidence;
        try { evidence=InstanceBoundaryOpenCv.evidenceFromPacked(packed,edge); }
        finally { evidenceNanos+=System.nanoTime()-evidenceStarted; }
        var view=new InstanceBoundaryMath.View(id,call.role(),call.sourceToInput(),mask,evidence);
        captured.add(new Captured(view,edge.clone(),flat(proposal.quad()),
                new double[]{(call.cueX()-1.5)/4,(call.cueY()-1.5)/4},call.stage(),call.retry(),returned));capturedViews++;
        }finally {captureNanos+=System.nanoTime()-captureStarted;}
    }
    List<Effective> apply(List<PictureContentDetector.Candidate> outputs,List<PicturePageDecode.Proposal> proposals,
                          List<PictureContentDetector.Refined> primary,List<PictureContentDetector.Refined> fallback,
                          List<PictureContentDetector.Refined> verification,List<Vertex[]> finals,
                          boolean[] overlaps,Size size){
        long applyStarted=System.nanoTime();
        try {
        var peers=new ArrayList<InstanceBoundaryMath.Peer>();
        for(int i=0;i<proposals.size();i++){
            var h=verification.get(i)!=null?verification.get(i):fallback.get(i);
            if(h!=null&&h.quad()!=null){var p=proposals.get(i);peers.add(new InstanceBoundaryMath.Peer("global:"+i,
                    flat(p.quad()),flat(h.quad()),new double[]{p.centerX(),p.centerY()}));}
        }
        var effective=new ArrayList<Effective>();
        for(int i=0;i<proposals.size();i++){
            var ordinary=outputs.get(i);var first=primary.get(i);var second=fallback.get(i);var checked=verification.get(i);
            var originalBranch=PictureContentDetector.selectedForLocal(first,second,finals.get(i),overlaps[i]);
            var selected=originalBranch.refined();String role=second==null?"primary":"helper";
            var originalProvenance=new Provenance("existing",role,second==null?"primary":"fallback",null,null,selected);
            var current=new InstanceBoundaryMath.Candidate("current",flat(selected.quad()),new int[]{0},originalProvenance);
            var p=proposals.get(i);String lineage="global:"+i;
            var calls=new ArrayList<Captured>();
            for(var capturedCall:captured)if(capturedCall.view.id.startsWith(lineage+"/"))calls.add(capturedCall);
            boolean veto=second==null&&PictureContentDetector.semanticBackground(checked);
            InstanceBoundaryMath.Choice choice;
            if(veto||!InstanceBoundaryMath.sourceValid(current.quad,size.getWidth(),size.getHeight())){
                choice=new InstanceBoundaryMath.Choice(veto?"semantic_background_veto":"no_valid_current",false,current,
                        null,null,0,false,false,false);
            }else{
                if(calls.isEmpty())throw new IllegalStateException("C1 valid current without observed global view");
                long featureStarted=System.nanoTime();
                var views=new ArrayList<InstanceBoundaryMath.View>();
                for(var capturedCall:calls)views.add(capturedCall.view());
                var features=new InstanceBoundaryMath.Features(views,flat(p.quad()),
                        new double[]{p.centerX(),p.centerY()},size.getWidth(),size.getHeight(),peers,backend);
                featureNanos+=System.nanoTime()-featureStarted;
                var h=second!=null?second:checked;
                // Shared exact activation/current-risk probe. Inactive/unknown cases need no generator.
                long scoringStarted=System.nanoTime();
                choice=InstanceBoundaryMath.choose(features,current,List.of(),flat(first.quad()),h==null?null:flat(h.quad()),selected.contentSupported(),veto);
                scoringNanos+=System.nanoTime()-scoringStarted;
                if("retain_margin".equals(choice.decision)) {
                long generationStarted=System.nanoTime();
                var candidates=new ArrayList<InstanceBoundaryMath.Candidate>();
                for(int order=0;order<calls.size();order++){
                    var c=calls.get(order);String branch=c.view.role.equals("primary")?"primary":
                            c.stage.equals("global_fallback")?"fallback":"verifier";
                    var provenance=new Provenance("existing",c.view.role,branch,c.view.id,c,c.returned);
                    double[] returned=InstanceBoundaryMath.replacementGeometry(flat(c.returned.quad()),size.getWidth(),size.getHeight());
                    if(returned!=null)candidates.add(new InstanceBoundaryMath.Candidate(c.view.id+":returned",returned,
                            new int[]{c.view.role.equals("helper")?1:2,order},provenance));
                    var gridPeers=new ArrayList<InstanceBoundaryMath.Peer>();
                    for(var peer:peers)gridPeers.add(new InstanceBoundaryMath.Peer(peer.id,
                            InstanceBoundaryMath.sourceToGrid(peer.proposal,c.view.affine),
                            InstanceBoundaryMath.sourceToGrid(peer.helper,c.view.affine),
                            InstanceBoundaryMath.sourceToGrid(peer.center,c.view.affine)));
                    var pool=InstanceBoundaryMath.generate(c.view.evidence,
                            InstanceBoundaryMath.sourceToGrid(c.proposal,c.view.affine),c.cue,gridPeers,backend);
                    generatedPools++;generatedCandidates+=pool.candidates.size();
                    int generatedIndex=0;
                    for(var generated:pool.candidates){
                        double[] rawSource=InstanceBoundaryMath.gridToSource(generated.quad,c.view.affine);
                        int index=generatedIndex++;
                        if(!InstanceBoundaryMath.sourceValid(rawSource,size.getWidth(),size.getHeight()))continue;
                        double[] q=InstanceBoundaryMath.replacementGeometry(rawSource,size.getWidth(),size.getHeight());
                        if(q==null)continue;
                        int[] tie={3,order,generated.sideIndices[0],generated.sideIndices[1],generated.sideIndices[2],generated.sideIndices[3]};
                        candidates.add(new InstanceBoundaryMath.Candidate(c.view.id+":generated:"+index,q,tie,
                                new Provenance("generated",c.view.role,branch,c.view.id,c,c.returned)));
                    }
                }
                generationNanos+=System.nanoTime()-generationStarted;
                scoringStarted=System.nanoTime();
                choice=InstanceBoundaryMath.choose(features,current,candidates,flat(first.quad()),h==null?null:flat(h.quad()),selected.contentSupported(),veto);
                scoringNanos+=System.nanoTime()-scoringStarted;
                }
            }
            var provenance=(Provenance)choice.selected.provenance;boolean generated=provenance.kind.equals("generated");
            Vertex[] chosen=choice.changed?vertices(choice.selected.quad):selected.quad();
            PictureContentDetector.Refined chosenFit=choice.changed?provenance.returned:selected;
            CropReviewPolicy.FitEvidence review=chosenFit.fitEvidence();String scope="original_actual_fit";
            if(choice.changed&&generated){
                Captured c=provenance.captured;
                review=PictureQuadFit.reviewAtSourceQuad(c.view.mask,c.edge,96,96,c.view.affine,matrix(choice.selected.quad));
                chosenFit=new PictureContentDetector.Refined(chosen,"instance_boundary_review",0,chosenFit.fitReasons(),
                        chosenFit.firstMask(),false,review);
                scope="generated_outline_against_actual_generating_roi_mask_no_confident_support";
            }else if(choice.changed){
                // Retain actual returned evidence, using exactly the stored source float geometry.
                chosenFit=new PictureContentDetector.Refined(chosen,chosenFit.reason(),chosenFit.borderContacts(),chosenFit.fitReasons(),
                        chosenFit.firstMask(),chosenFit.contentSupported(),review,chosenFit.reviewReference());
            }
            boolean accepted=!generated&&second!=null&&originalBranch.acceptedFallback()&&sameGeometry(flat(chosen),flat(second.quad()));
            var independent=provenance.role.equals("helper")?first:(checked!=null?checked:second);
            boolean corroborated=independent!=null&&independent.contentSupported()
                    &&InstanceBoundaryMath.sourceValid(flat(independent.quad()),size.getWidth(),size.getHeight())
                    &&InstanceBoundaryMath.sourceValid(flat(chosen),size.getWidth(),size.getHeight())
                    &&InstanceBoundaryMath.iou(flat(chosen),flat(independent.quad()))>=.80;
            boolean reviewed=generated||!chosenFit.reason().isEmpty()||(!choice.changed&&originalBranch.reviewedBeforeVerifier());
            float[] corners=choice.changed?PicturePageDecode.canvasCorners(chosen,size):ordinary.corners;
            // Detach 96-grid maps before local routing. Diagnostics retain only actual returned fit and scalar measurements.
            provenance=new Provenance(provenance.kind,provenance.role,provenance.branch,provenance.callId,null,provenance.returned);
            var detachedSelected=new InstanceBoundaryMath.Candidate(choice.selected.id,choice.selected.quad,choice.selected.tieRank,provenance);
            choice=new InstanceBoundaryMath.Choice(choice.decision,choice.changed,detachedSelected,choice.features,choice.originalFeatures,
                    choice.validAlternatives,choice.disagreement,choice.unsupported,choice.peerConflict);
            var result=new Effective(new PictureContentDetector.SelectedBranch(chosenFit,accepted,reviewed),corroborated,choice,
                    provenance,ordinary.corners,corners,scope);
            effective.add(result);
            if(choice.changed)outputs.set(i,new PictureContentDetector.Candidate(corners,chosenFit.reason(),ordinary.fallback,
                    ordinary.disposition,ordinary.verifier,ordinary.lineage,review,null,result));
        }
        return List.copyOf(effective);
        } finally {applyNanos+=System.nanoTime()-applyStarted;}
    }
    private static boolean sameGeometry(double[] a,double[] b){
        if(a==null||b==null||a.length!=b.length)return false;
        for(int i=0;i<a.length;i++)if(a[i]!=b[i])return false;
        return true;
    }
    static double[] flat(Vertex[] q){if(q==null)return null;double[] a=new double[q.length*2];for(int i=0;i<q.length;i++){a[2*i]=q[i].x();a[2*i+1]=q[i].y();}return a;}
    static Vertex[] vertices(double[] q){if(q==null)return null;Vertex[] v=new Vertex[4];for(int i=0;i<4;i++)v[i]=new Vertex((float)q[2*i],(float)q[2*i+1]);return v;}
    static double[][] matrix(double[] q){double[][] a=new double[4][2];for(int i=0;i<4;i++){a[i][0]=q[2*i];a[i][1]=q[2*i+1];}return a;}
}
