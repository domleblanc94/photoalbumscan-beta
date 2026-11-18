package local.photoscan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;

/** Frozen C1 experiment. No models, references, provenance mutation, or pipeline routing. */
final class InstanceBoundaryMath {
    static final int GRID = 96, CELLS = GRID * GRID, MAX_VIEWS = 4;
    private InstanceBoundaryMath() {}

    /** Operations whose float32/OpenCV behavior is part of the frozen recipe. */
    interface Backend {
        double peerIou(double[] a, double[] b);
        double[] lineScores(List<Line> lines, double[] proposal, float[] evidence);
    }

    static final class View {
        final String id, role;
        final double[] affine, inverse;
        final float[] mask, evidence;
        View(String id, String role, double[] affine, float[] mask, float[] evidence) {
            if (!("primary".equals(role) || "helper".equals(role))) throw new IllegalArgumentException("role");
            requireAffine(affine); requireMap(mask); requireMap(evidence);
            this.id = id; this.role = role; this.affine = affine.clone();
            this.inverse = inverseLinear(affine);
            this.mask = mask.clone(); this.evidence = evidence.clone();
        }
    }
    static final class Peer {
        final String id; final double[] proposal, helper, center;
        Peer(String id, double[] proposal, double[] helper, double[] center) {
            this.id = id; this.proposal = copy(proposal); this.helper = copy(helper); this.center = copy(center);
        }
    }
    static final class AcceptedPeer {
        final String id; final double[] quad, center; final double agreement;
        AcceptedPeer(String id, double[] quad, double[] center, double agreement) {
            this.id=id; this.quad=quad.clone(); this.center=center.clone(); this.agreement=agreement;
        }
    }
    static final class Line {
        final double nx, ny, rho; final String origin; double support;
        Line(double nx, double ny, double rho, String origin) {
            this.nx=nx; this.ny=ny; this.rho=rho; this.origin=origin;
        }
    }
    static final class Generated {
        final double[] quad; final int[] sideIndices; final double boundarySupport;
        final List<String> peerConflicts;
        Generated(double[] quad, int[] sideIndices, double boundarySupport, List<String> peerConflicts) {
            this.quad=quad; this.sideIndices=sideIndices; this.boundarySupport=boundarySupport;
            this.peerConflicts=List.copyOf(peerConflicts);
        }
    }
    static final class Pool {
        final List<Generated> candidates; final List<List<Line>> sideLines;
        final List<AcceptedPeer> peers; final String reason;
        Pool(List<Generated> candidates,List<List<Line>> sideLines,List<AcceptedPeer> peers,String reason) {
            this.candidates=List.copyOf(candidates); this.sideLines=List.copyOf(sideLines);
            this.peers=List.copyOf(peers); this.reason=reason;
        }
    }
    static final class Candidate {
        final String id; final double[] quad; final int[] tieRank; final Object provenance;
        Candidate(String id,double[] quad,int[] tieRank,Object provenance) {
            this.id=id; this.quad=copy(quad); this.tieRank=tieRank.clone(); this.provenance=provenance;
        }
    }
    static final class Measurement {
        final double risk,boundary,maskFn,maskFp,peerOverlap,l1Disagreement;
        final double[] sideSupport; final int observedCandidateCells;
        Measurement(double risk,double boundary,double maskFn,double maskFp,double peerOverlap,
                    double l1Disagreement,double[] sideSupport,int observedCandidateCells) {
            this.risk=risk; this.boundary=boundary; this.maskFn=maskFn; this.maskFp=maskFp;
            this.peerOverlap=peerOverlap; this.l1Disagreement=l1Disagreement;
            this.sideSupport=sideSupport; this.observedCandidateCells=observedCandidateCells;
        }
    }
    static final class Choice {
        final String decision; final boolean changed; final Candidate selected;
        final Measurement features, originalFeatures; final int validAlternatives;
        final boolean disagreement,unsupported,peerConflict;
        Choice(String decision,boolean changed,Candidate selected,Measurement features,Measurement original,
               int valid,boolean disagreement,boolean unsupported,boolean peerConflict) {
            this.decision=decision; this.changed=changed; this.selected=selected; this.features=features;
            originalFeatures=original; validAlternatives=valid; this.disagreement=disagreement;
            this.unsupported=unsupported; this.peerConflict=peerConflict;
        }
    }

    /**
     * Per-call grid pool, NOT locator-source geometry: proposal is the actual call's
     * proposal_source projected through that call's affine (including retry calls).
     * Cue is (that call's ROI/input center - 1.5) / 4. Peers are projected into the
     * same call grid. The chooser separately receives locator source proposal/cue.
     */
    static Pool generate(float[] evidence,double[] proposal,double[] cue,List<Peer> peers,Backend backend) {
        if (!validGridQuad(proposal) || !finitePoint(cue))
            return new Pool(List.of(),List.of(),List.of(),"invalid_proposal_or_cue");
        requireMap(evidence);
        List<AcceptedPeer> accepted=corroboratedPeers(peers,proposal,cue,backend);
        List<List<Line>> sides=new ArrayList<>();
        for (int side=0;side<4;side++) {
            checkInterrupted();
            int next=(side+1)%4; double ax=proposal[2*side],ay=proposal[2*side+1];
            double bx=proposal[2*next],by=proposal[2*next+1],dx=bx-ax,dy=by-ay;
            double length=Math.hypot(dx,dy),baseX=dy/length,baseY=-dx/length;
            List<Line> lines=new ArrayList<>();
            lines.add(new Line(baseX,baseY,baseX*ax+baseY*ay,"proposal"));
            for (int angle : new int[]{-12,-6,0,6,12}) {
                double radians=Math.toRadians(angle),cs=Math.cos(radians),sn=Math.sin(radians);
                double nx=cs*baseX-sn*baseY,ny=sn*baseX+cs*baseY;
                double low=Double.POSITIVE_INFINITY,high=Double.NEGATIVE_INFINITY;
                for (double x : new double[]{-.5,95.5}) for (double y : new double[]{-.5,95.5}) {
                    double p=nx*x+ny*y; low=Math.min(low,p); high=Math.max(high,p);
                }
                low=Math.max(cue[0]*nx+cue[1]*ny+.5,low);
                for (int rho=(int)Math.ceil(low);rho<=Math.floor(high);rho++)
                    lines.add(new Line(nx,ny,rho,"edge_rgb_search"));
            }
            for (AcceptedPeer peer : accepted) for(int j=0;j<4;j++) {
                int k=(j+1)%4; double px=peer.quad[2*j],py=peer.quad[2*j+1];
                double ex=peer.quad[2*k]-px,ey=peer.quad[2*k+1]-py,l=Math.hypot(ex,ey);
                double nx=ey/l,ny=-ex/l;
                if(nx*baseX+ny*baseY<0){nx=-nx;ny=-ny;}
                double rho=nx*px+ny*py;
                if(nx*baseX+ny*baseY>=Math.cos(Math.toRadians(12)) && rho>=nx*cue[0]+ny*cue[1]+.5)
                    lines.add(new Line(nx,ny,rho,"corroborated_peer_side"));
            }
            double[] scores=backend.lineScores(lines,proposal,evidence);
            if(scores.length!=lines.size())throw new IllegalArgumentException("line score count");
            for(int i=0;i<scores.length;i++)lines.get(i).support=scores[i];
            List<Integer> order=new ArrayList<>();for(int i=1;i<lines.size();i++)order.add(i);
            order.sort((i,j)->{
                int c=Double.compare(scores[j],scores[i]); if(c!=0)return c;
                c=lines.get(i).origin.compareTo(lines.get(j).origin);if(c!=0)return c;
                c=Double.compare(lines.get(i).rho,lines.get(j).rho);return c!=0?c:Integer.compare(i,j);
            });
            List<Line> selected=new ArrayList<>();selected.add(lines.get(0));
            for(int index:order){
                Line line=lines.get(index);boolean crowded=false;
                for(Line prior:selected){
                    double nx=line.nx-prior.nx,ny=line.ny-prior.ny,offset=-line.rho+prior.rho;
                    if(Math.max(Math.abs(nx*ax+ny*ay+offset),Math.abs(nx*bx+ny*by+offset))<2){crowded=true;break;}
                }
                if(!crowded)selected.add(line);if(selected.size()==4)break;
            }
            sides.add(List.copyOf(selected));
        }
        List<Generated> generated=new ArrayList<>();
        for(int a=0;a<sides.get(0).size();a++)for(int b=0;b<sides.get(1).size();b++)
            for(int c=0;c<sides.get(2).size();c++)for(int d=0;d<sides.get(3).size();d++){
                int[] indices={a,b,c,d};double[] q=new double[8];boolean keep=true;double support=0;
                for(int s=0;s<4;s++){
                    Line line=sides.get(s).get(indices[s]),prev=sides.get((s+3)%4).get(indices[(s+3)%4]);
                    double det=prev.nx*line.ny-prev.ny*line.nx;
                    if(Math.abs(det)<.1){keep=false;break;}
                    q[2*s]=(prev.rho*line.ny-prev.ny*line.rho)/det;
                    q[2*s+1]=(prev.nx*line.rho-prev.rho*line.nx)/det;
                    if(line.nx*cue[0]+line.ny*cue[1]>line.rho)keep=false;
                    support+=line.support;
                }
                if(!keep||!validGridQuad(q))continue;
                for(double x:q)if(x<-.5||x>95.5)keep=false;
                if(!keep)continue;
                List<String> conflicts=new ArrayList<>();double[] floatQ=toFloat(q);
                for(AcceptedPeer p:accepted)if(inside((float)p.center[0],(float)p.center[1],floatQ))conflicts.add(p.id);
                generated.add(new Generated(q,indices,support/4,conflicts));
            }
        return new Pool(generated,sides,accepted,"");
    }

    static List<AcceptedPeer> corroboratedPeers(List<Peer> peers,double[] proposal,double[] cue,Backend backend) {
        List<AcceptedPeer> accepted=new ArrayList<>();
        for(Peer p:peers){
            if(!validGridQuad(p.proposal)||!validGridQuad(p.helper))continue;
            double agreement=backend.peerIou(p.proposal,p.helper);
            if(agreement<.80||backend.peerIou(p.proposal,proposal)>.27||!finitePoint(p.center))continue;
            boolean duplicate=false;
            for(AcceptedPeer prior:accepted)if(backend.peerIou(p.proposal,prior.quad)>.27){duplicate=true;break;}
            if(!duplicate)accepted.add(new AcceptedPeer(p.id,p.proposal,p.center,agreement));
        }
        accepted.sort(Comparator.comparingDouble((AcceptedPeer p)->Math.hypot(p.center[0]-cue[0],p.center[1]-cue[1]))
                .thenComparing(p->p.id));
        return List.copyOf(accepted.subList(0,Math.min(4,accepted.size())));
    }

    static final class Features {
        final List<View> views; final double[] proposal,cue,commonAffine;
        final int width,height; final List<AcceptedPeer> peers;
        final double[] sourceX=new double[CELLS],sourceY=new double[CELLS],probability=new double[CELLS];
        final boolean[] observed=new boolean[CELLS]; final double foregroundMass;
        Features(List<View> views,double[] proposal,double[] cue,int width,int height,List<Peer> peers,Backend backend){
            if(views.isEmpty()||views.size()>MAX_VIEWS)throw new IllegalArgumentException("view budget");
            if(width<=0||height<=0||!finitePoint(cue)||!finite(proposal,8))throw new IllegalArgumentException("geometry");
            this.views=List.copyOf(views);this.proposal=proposal.clone();this.cue=cue.clone();this.width=width;this.height=height;
            View first=null;for(View v:views)if("primary".equals(v.role)){first=v;break;}
            if(first==null)throw new IllegalArgumentException("first primary required");commonAffine=first.affine.clone();
            List<Peer> gridPeers=new ArrayList<>();
            for(Peer p:peers)gridPeers.add(new Peer(p.id,sourceToGrid(p.proposal,commonAffine),
                    sourceToGrid(p.helper,commonAffine),sourceToGrid(p.center,commonAffine)));
            List<AcceptedPeer> accepted=corroboratedPeers(gridPeers,sourceToGrid(proposal,commonAffine),sourceToGrid(cue,commonAffine),backend);
            List<AcceptedPeer> sourcePeers=new ArrayList<>();
            for(AcceptedPeer p:accepted)sourcePeers.add(new AcceptedPeer(p.id,gridToSource(p.quad,commonAffine),gridToSource(p.center,commonAffine),p.agreement));
            this.peers=List.copyOf(sourcePeers);
            double mass=0;
            for(int y=0;y<GRID;y++)for(int x=0;x<GRID;x++){
                int i=y*GRID+x;double[] source=gridToSource(new double[]{x,y},commonAffine);
                sourceX[i]=source[0];sourceY[i]=source[1];
                if(!sourceObserved(source[0],source[1],width,height))continue;
                double[] sums=new double[2];int[] counts=new int[2];
                for(View v:views){
                    double[] grid=sourceToGrid(source,v.affine);
                    if(gridObserved(grid[0],grid[1])){int r="primary".equals(v.role)?0:1;sums[r]+=sample(v.mask,grid[0],grid[1]);counts[r]++;}
                }
                int roleCount=0;double total=0;
                for(int r=0;r<2;r++)if(counts[r]>0){roleCount++;total+=sums[r]/counts[r];}
                observed[i]=roleCount>0;probability[i]=roleCount==0?0:total/roleCount;
                for(AcceptedPeer p:this.peers)if(inside(source[0],source[1],p.quad)){probability[i]=0;break;}
                mass+=probability[i]*(observed[i]?1:0);
            }
            foregroundMass=mass;
        }
        double peerOverlap(double[] q){
            double overlap=0;for(AcceptedPeer p:peers)overlap=Math.max(overlap,intersection(q,p.quad)/Math.max(1e-12,Math.abs(signedArea(p.quad))));
            return overlap;
        }
        Measurement measure(double[] q){
            if(!sourceValid(q,width,height)||!inside(cue[0],cue[1],q)||foregroundMass<=1e-12)return null;
            int count=0;double missed=0,background=0;
            for(int i=0;i<CELLS;i++)if(observed[i]){
                if(inside(sourceX[i],sourceY[i],q)){count++;background+=1-probability[i];}else missed+=probability[i];
            }
            if(count==0)return null;
            double[] sideSupport=new double[4],values=new double[64];
            for(int side=0;side<4;side++){
                int next=(side+1)%4;double ax=q[2*side],ay=q[2*side+1],dx=q[2*next]-ax,dy=q[2*next+1]-ay;
                double[] nx=new double[views.size()],ny=new double[views.size()];
                for(int j=0;j<views.size();j++){
                    View v=views.get(j);double gx=(v.affine[0]*dx+v.affine[1]*dy)/4,gy=(v.affine[3]*dx+v.affine[4]*dy)/4;
                    double len=Math.hypot(gx,gy);if(len<=1e-12)return null;nx[j]=gy/len;ny[j]=-gx/len;
                }
                for(int k=0;k<64;k++){
                    double t=k/63.0,sx=ax+dx*t,sy=ay+dy*t;
                    double primarySum=0,helperSum=0;int primaryCount=0,helperCount=0;
                    for(int j=0;j<views.size();j++){
                        View v=views.get(j);double[] affine=v.affine;
                        double gx=(sx*affine[0]+sy*affine[1]+affine[2]-1.5)/4;
                        double gy=(sx*affine[3]+sy*affine[4]+affine[5]-1.5)/4;
                        // Frozen support denominator is central map validity; offsets are independently physical-source masked.
                        if(!gridObserved(gx,gy))continue;
                        double maximum=0;
                        for(int delta=-1;delta<=1;delta++)maximum=Math.max(maximum,observedBandSample(v.evidence,
                                gx+delta*nx[j],gy+delta*ny[j],affine,v.inverse,width,height));
                        if("primary".equals(v.role)){primarySum+=maximum;primaryCount++;}
                        else {helperSum+=maximum;helperCount++;}
                    }
                    if(primaryCount+helperCount==0)return null;
                    values[k]=Math.max(primaryCount==0?0:primarySum/primaryCount,helperCount==0?0:helperSum/helperCount);
                }
                sideSupport[side]=.75*mean(values)+.25*quantile(values,.25);
            }
            double boundary=.5*mean(sideSupport)+.5*minimum(sideSupport),fn=missed/foregroundMass,fp=background/count;
            double peer=peerOverlap(q),prior=1-iou(q,proposal);
            double risk=.55*(1-boundary)+.20*((fn+fp)/2)+.20*peer+.05*prior;
            return new Measurement(risk,boundary,fn,fp,peer,prior,sideSupport,count);
        }
    }

    static Choice choose(Features features,Candidate current,List<Candidate> candidates,double[] primary,double[] helper,
                         boolean contentSupported,boolean veto){
        if(veto)return abstain("semantic_background_veto",current);
        if(current==null||!sourceValid(current.quad,features.width,features.height))return abstain("no_valid_current",current);
        boolean disagreement=sourceValid(primary,features.width,features.height)&&sourceValid(helper,features.width,features.height)&&iou(primary,helper)<.80;
        boolean peerConflict=features.peerOverlap(current.quad)>=.50;
        if(!(disagreement||!contentSupported||peerConflict))return abstain("inactive",current);
        Measurement original=features.measure(current.quad);
        if(original==null)return abstain("current_feature_unknown",current);
        Candidate best=current;Measurement measurement=original;int valid=0;
        List<Candidate> sorted=new ArrayList<>(candidates);sorted.sort((a,b)->compareRanks(a.tieRank,b.tieRank));
        for(Candidate candidate:sorted){
            checkInterrupted();
            Measurement m=features.measure(candidate.quad);
            if(m==null||minimum(m.sideSupport)<.25)continue;
            valid++;if(m.risk<measurement.risk){best=candidate;measurement=m;}
        }
        boolean changed=!best.id.equals(current.id)&&original.risk-measurement.risk>=.03;
        return new Choice(changed?"switch":"retain_margin",changed,changed?best:current,changed?measurement:original,
                original,valid,disagreement,!contentSupported,peerConflict);
    }
    private static Choice abstain(String decision,Candidate current){return new Choice(decision,false,current,null,null,0,false,false,false);}
    private static int compareRanks(int[] a,int[] b){for(int i=0;i<Math.min(a.length,b.length);i++){int c=Integer.compare(a[i],b[i]);if(c!=0)return c;}return Integer.compare(a.length,b.length);}

    static double signedArea(double[] q){double sum=0;for(int i=0,n=q.length/2;i<n;i++){int j=(i+1)%n;sum+=q[2*i]*q[2*j+1]-q[2*i+1]*q[2*j];}return sum/2;}
    static boolean sourceValid(double[] q,int width,int height){
        if(!finite(q,8)||width<=0||height<=0)return false;
        for(int i=0;i<8;i+=2)if(!sourceObserved(q[i],q[i+1],width,height))return false;
        return convex(q,0,0)&&signedArea(q)>=4;
    }
    static boolean validGridQuad(double[] q){return finite(q,8)&&convex(q,1e-6,2)&&Math.abs(signedArea(q))>=16;}
    private static boolean convex(double[] q,double epsilon,double minimumSide){
        for(int i=0;i<4;i++){
            int j=(i+1)%4,k=(i+2)%4;double dx=q[2*j]-q[2*i],dy=q[2*j+1]-q[2*i+1];
            if(!(dx*(q[2*k+1]-q[2*j+1])-dy*(q[2*k]-q[2*j])>epsilon)||Math.hypot(dx,dy)<minimumSide)return false;
        }return true;
    }
    static boolean inside(double x,double y,double[] q){
        for(int i=0,n=q.length/2;i<n;i++){int j=(i+1)%n;double dx=q[2*j]-q[2*i],dy=q[2*j+1]-q[2*i+1];if((y-q[2*i+1])*dx-(x-q[2*i])*dy<0)return false;}return true;
    }
    static double intersection(double[] a,double[] b){
        double[] subject=a.clone();
        for(int j=0,n=b.length/2;j<n;j++){
            int k=(j+1)%n;double sx=b[2*j],sy=b[2*j+1],ex=b[2*k]-sx,ey=b[2*k+1]-sy;
            if(ex==0&&ey==0)continue;double[] clipped=new double[subject.length+8];int used=0;
            for(int i=0,m=subject.length/2;i<m;i++){
                int p=(i+m-1)%m;double cx=subject[2*i],cy=subject[2*i+1],px=subject[2*p],py=subject[2*p+1];
                double d0=(py-sy)*ex-(px-sx)*ey,d1=(cy-sy)*ex-(cx-sx)*ey;
                if((d0>=0)!=(d1>=0)){double t=d0/(d0-d1);clipped[used++]=px+(cx-px)*t;clipped[used++]=py+(cy-py)*t;}
                if(d1>=0){clipped[used++]=cx;clipped[used++]=cy;}
            }
            if(used<6)return 0;subject=Arrays.copyOf(clipped,used);
        }return Math.abs(signedArea(subject));
    }
    static double iou(double[] a,double[] b){double aa=Math.abs(signedArea(a)),ba=Math.abs(signedArea(b)),shared=Math.min(intersection(a,b),Math.min(aa,ba));return shared/Math.max(1e-12,aa+ba-shared);}
    static double[] sourceToGrid(double[] source,double[] a){
        if(source==null)return null;double[] out=new double[source.length];
        for(int i=0;i<source.length;i+=2){out[i]=(source[i]*a[0]+source[i+1]*a[1]+a[2]-1.5)/4;out[i+1]=(source[i]*a[3]+source[i+1]*a[4]+a[5]-1.5)/4;}return out;
    }
    static double[] gridToSource(double[] grid,double[] a){
        if(grid==null)return null;double[] out=new double[grid.length],inverse=inverseLinear(a);
        for(int i=0;i<grid.length;i+=2){double x=grid[i]*4+1.5-a[2],y=grid[i+1]*4+1.5-a[5];
            out[i]=x*inverse[0]+y*inverse[1];out[i+1]=x*inverse[2]+y*inverse[3];}return out;
    }
    static double sample(float[] image,double x,double y){
        if(!gridObserved(x,y))return 0;
        int ix=(int)Math.floor(x),iy=(int)Math.floor(y),xx=Math.min(ix+1,95),yy=Math.min(iy+1,95);
        double fx=x-ix,fy=y-iy;
        return image[iy*96+ix]*(1-fx)*(1-fy)+image[iy*96+xx]*fx*(1-fy)
                +image[yy*96+ix]*(1-fx)*fy+image[yy*96+xx]*fx*fy;
    }
    static double observedBandSample(float[] image,double x,double y,double[] affine,int width,int height){
        return observedBandSample(image,x,y,affine,inverseLinear(affine),width,height);
    }
    private static double observedBandSample(float[] image,double x,double y,double[] affine,double[] inverse,int width,int height){
        if(!gridObserved(x,y))return 0;
        // Frozen host explicitly forms inverse first, then multiplies; direct division moves boundary cells.
        double xx=x*4+1.5-affine[2],yy=y*4+1.5-affine[5];
        double sx=xx*inverse[0]+yy*inverse[1],sy=xx*inverse[2]+yy*inverse[3];
        return sourceObserved(sx,sy,width,height)?sample(image,x,y):0;
    }
    private static double[] inverseLinear(double[] a){
        // Native captured ROI affines are diagonal. Preserve NumPy inverse's reciprocal before multiplication.
        if(a[1]==0&&a[3]==0)return new double[]{1/a[0],0,0,1/a[4]};
        if(a[0]==0&&a[4]==0)return new double[]{0,1/a[3],1/a[1],0};
        double det=a[0]*a[4]-a[1]*a[3];return new double[]{a[4]/det,-a[1]/det,-a[3]/det,a[0]/det};
    }
    static double[] nativeStoredSource(double[] q,int width,int height){
        double[] out=new double[q.length];for(int i=0;i<q.length;i++){float normalized=((float)q[i]+.5f)/(float)(i%2==0?width:height);out[i]=(double)normalized*(i%2==0?width:height)-.5;}return out;
    }
    static double[] replacementGeometry(double[] q,int width,int height){
        if(q==null)return null;double[] source=toFloat(q);return sourceValid(source,width,height)&&sourceValid(nativeStoredSource(source,width,height),width,height)?source:null;
    }
    static double quantile(double[] values,double p){double[] sorted=values.clone();Arrays.sort(sorted);double i=(sorted.length-1)*p;int lo=(int)Math.floor(i),hi=(int)Math.ceil(i);return sorted[lo]+(sorted[hi]-sorted[lo])*(i-lo);}
    static double mean(double[] values){double sum=0;for(double x:values)sum+=x;return sum/values.length;}
    static double minimum(double[] values){double min=Double.POSITIVE_INFINITY;for(double x:values)min=Math.min(min,x);return min;}
    static double[] toFloat(double[] q){double[] out=new double[q.length];for(int i=0;i<q.length;i++)out[i]=(float)q[i];return out;}
    private static boolean sourceObserved(double x,double y,int width,int height){return x>=0&&y>=0&&x<=width-1&&y<=height-1;}
    private static boolean gridObserved(double x,double y){return x>=0&&y>=0&&x<=95&&y<=95;}
    private static boolean finite(double[] q,int size){if(q==null||q.length!=size)return false;for(double x:q)if(!Double.isFinite(x))return false;return true;}
    private static boolean finitePoint(double[] p){return finite(p,2);}
    private static double[] copy(double[] a){return a==null?null:a.clone();}
    private static void checkInterrupted(){if(Thread.currentThread().isInterrupted())throw new CancellationException("C1 interrupted");}
    private static void requireAffine(double[] affine){if(!finite(affine,6)||affine[0]*affine[4]-affine[1]*affine[3]==0)throw new IllegalArgumentException("affine");}
    private static void requireMap(float[] map){if(map==null||map.length!=CELLS)throw new IllegalArgumentException("map shape");for(float x:map)if(!Float.isFinite(x)||x<0||x>1)throw new IllegalArgumentException("map range");}
}
