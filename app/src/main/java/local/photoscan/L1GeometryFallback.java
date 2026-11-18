package local.photoscan;

import java.util.*;

/** Fixed R7 post-arbitration geometry. No model calls or admission decisions. */
final class L1GeometryFallback {
    static final String POLICY="l1-failed-helper-envelope-v1";
    static final double CONTAINMENT_PX=.001;
    record Helper(boolean ran,boolean quadNull,String action,String primaryReason,String reason,
                  boolean geometryInvalid,Double quadIou,boolean firstMaskPresent,boolean finite,boolean empty,int activePixels) {}
    record Input(String id,float[] ordinary,String retainedReason,boolean provenanceMatched,double[][] raw,double[] cue,
                 boolean primary,Helper helper) {}
    record Correction(float[] original,float[] proposed,double[][] source,boolean accepted,String reason,
                      double rawCoverage,double areaRatio,double minimumDistance,double originalRetention) {
        float[] selected(){return accepted?proposed:original;}
    }
    record Item(String id,float[] original) {}
    record Conflict(String first,String second,double oldRatio,double newRatio) {}
    record Page(LinkedHashMap<String,float[]> corners,boolean reverted,List<Conflict> conflicts) {}
    static double area(double[][] q){double sum=0;for(int i=0;i<q.length;i++){double[] a=q[i],b=q[(i+1)%q.length];sum+=a[0]*b[1]-a[1]*b[0];}return Math.abs(sum)*.5;}
    static double cross(double[] a,double[] b,double[] c){return (b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0]);}
    static boolean valid(double[][] q,int width,int height){
        if(q==null||q.length!=4)return false;
        for(double[] p:q)if(p==null||p.length!=2||!Double.isFinite(p[0])||!Double.isFinite(p[1])||(width>0&&(p[0]<0||p[1]<0||p[0]>width-1||p[1]>height-1)))return false;
        for(int i=0;i<4;i++)if(!(cross(q[i],q[(i+1)%4],q[(i+2)%4])>1e-9))return false;return true;
    }
    static double minimumDistance(double[][] q,double[][] points){
        double minimum=Double.POSITIVE_INFINITY;
        for(int i=0;i<4;i++){double[] a=q[i],b=q[(i+1)%4];double length=Math.hypot(b[0]-a[0],b[1]-a[1]);for(double[] p:points)minimum=Math.min(minimum,cross(a,b,p)/length);}return minimum;
    }
    static double intersection(double[][] first,double[][] second){
        List<double[]> polygon=new ArrayList<>();for(double[] p:first)polygon.add(p.clone());
        for(int side=0;side<4;side++){
            if(polygon.isEmpty())return 0;double[] a=second[side],b=second[(side+1)%4];List<double[]> output=new ArrayList<>();
            for(int i=0;i<polygon.size();i++){double[] p=polygon.get(i),q=polygon.get((i+1)%polygon.size());double fp=cross(a,b,p),fq=cross(a,b,q);boolean ip=fp>=0,iq=fq>=0;
                if(ip)output.add(p);if(ip!=iq&&Math.abs(fp-fq)>1e-15){double t=fp/(fp-fq);output.add(new double[]{p[0]+(q[0]-p[0])*t,p[1]+(q[1]-p[1])*t});}}
            polygon=output;
        }return polygon.size()<3?0:area(polygon.toArray(new double[0][]));
    }
    static double[][] envelope(double[][] raw,double[][] old){
        if(!valid(raw,0,0)||!valid(old,0,0))return null;double[][] n=new double[4][2];double[] offset=new double[4];
        for(int i=0;i<4;i++){double[] a=raw[i],b=raw[(i+1)%4];double length=Math.hypot(b[0]-a[0],b[1]-a[1]);n[i][0]=-(b[1]-a[1])/length;n[i][1]=(b[0]-a[0])/length;offset[i]=n[i][0]*a[0]+n[i][1]*a[1];for(double[] p:old)offset[i]=Math.min(offset[i],n[i][0]*p[0]+n[i][1]*p[1]);}
        double[][] result=new double[4][2];
        for(int i=0;i<4;i++){int j=(i+3)%4;double[] a=n[j],b=n[i];double det=a[0]*b[1]-a[1]*b[0];if(Math.abs(det)<1e-9)return null;result[i][0]=(offset[j]*b[1]-a[1]*offset[i])/det;result[i][1]=(a[0]*offset[i]-offset[j]*b[0])/det;}
        return valid(result,0,0)&&minimumDistance(result,old)>=-1e-7&&minimumDistance(result,raw)>=-1e-7?result:null;
    }
    static double[][] source(float[] normalized,int width,int height){double[][] q=new double[4][2];for(int i=0;i<4;i++){q[i][0]=(double)normalized[2*i]*width-.5;q[i][1]=(double)normalized[2*i+1]*height-.5;}return q;}
    static float[] normalized(double[][] q,int width,int height){float[] v=new float[8];for(int i=0;i<4;i++){v[2*i]=((float)q[i][0]+.5f)/width;v[2*i+1]=((float)q[i][1]+.5f)/height;}return v;}
    static Correction reject(float[] old,String reason,double coverage,double ratio){return new Correction(old,null,null,false,reason,coverage,ratio,Double.NaN,Double.NaN);}
    static Correction propose(Input input,int width,int height){
        float[] ordinary=input.ordinary();double coverage=Double.NaN,ratio=Double.NaN;
        Helper h=input.helper();
        if(!input.primary()||input.id()==null||!input.id().startsWith("global:")||!input.provenanceMatched()
                ||!"weak_side".equals(input.retainedReason())||h==null||!h.ran()||!h.quadNull()
                ||!"inconclusive".equals(h.action())||!"weak_side".equals(h.primaryReason())||!"missing_quad".equals(h.reason())
                ||h.geometryInvalid()||h.quadIou()!=null||!h.firstMaskPresent()||!h.finite()||h.empty()||h.activePixels()<=0)
            return reject(ordinary,"failed_helper_guard_not_proven",coverage,ratio);
        if(input.id()==null||!input.id().startsWith("global:"))return reject(ordinary,"not_retained_global",coverage,ratio);
        if(width<2||height<2||width>65535||height>65535)return reject(ordinary,"unsupported_source_dimensions",coverage,ratio);
        if(ordinary==null||ordinary.length!=8)return reject(ordinary,"invalid_ordinary_quad",coverage,ratio);
        double[][] old=source(ordinary,width,height);if(!valid(old,width,height))return reject(ordinary,"invalid_ordinary_quad",coverage,ratio);
        if(!input.provenanceMatched())return reject(ordinary,"missing_retained_fit_provenance",coverage,ratio);
        if(!"weak_side".equals(input.retainedReason()))return reject(ordinary,"retained_fit_not_weak_side",coverage,ratio);
        double[][] raw=input.raw();if(!valid(raw,width,height))return reject(ordinary,"invalid_l1_quad",coverage,ratio);
        double[] cue=input.cue();if(cue==null||cue.length!=2||!Double.isFinite(cue[0])||!Double.isFinite(cue[1])||cue[0]<0||cue[1]<0||cue[0]>width-1||cue[1]>height-1)return reject(ordinary,"invalid_cue",coverage,ratio);
        if(minimumDistance(raw,new double[][]{cue})<-1e-7)return reject(ordinary,"cue_outside_l1",coverage,ratio);
        double oldArea=area(old),rawArea=area(raw);if(rawArea<=oldArea)return reject(ordinary,"l1_not_larger",coverage,ratio);
        coverage=intersection(old,raw)/rawArea;if(coverage>.85)return reject(ordinary,"l1_undercoverage_not_material",coverage,ratio);
        double[][] q=envelope(raw,old);if(q==null)return reject(ordinary,"invalid_envelope",coverage,ratio);ratio=area(q)/oldArea;
        if(!valid(q,width,height))return reject(ordinary,"envelope_outside_source",coverage,ratio);
        if(ratio>1.5)return reject(ordinary,"envelope_area_cap",coverage,ratio);
        float[] storedNormalized=normalized(q,width,height);double[][] stored=source(storedNormalized,width,height);
        if(!valid(stored,width,height))return reject(ordinary,"stored_envelope_outside_source",coverage,ratio);
        double distance=minimumDistance(stored,old);if(distance<-CONTAINMENT_PX)return reject(ordinary,"stored_envelope_loses_original",coverage,ratio);
        if(area(stored)>1.5*oldArea)return reject(ordinary,"stored_envelope_area_cap",coverage,ratio);
        return new Correction(ordinary,storedNormalized,stored,true,"failed_helper_l1_outward_envelope",coverage,area(stored)/oldArea,distance,intersection(stored,old)/oldArea);
    }
    static Page applyPage(List<Item> ordinary,Map<String,Correction> proposals,int width,int height){
        LinkedHashMap<String,float[]> original=new LinkedHashMap<>(),selected=new LinkedHashMap<>();
        for(Item item:ordinary){if(original.put(item.id(),item.original())!=null)throw new IllegalArgumentException("Duplicate lineage");selected.put(item.id(),item.original());}
        if(!original.keySet().containsAll(proposals.keySet()))throw new IllegalArgumentException("Cannot resurrect lineage");
        for(var e:proposals.entrySet()){Correction c=e.getValue();if(c.original()!=original.get(e.getKey()))throw new IllegalArgumentException("Original identity mismatch");if(c.accepted())selected.put(e.getKey(),c.proposed());}
        List<Conflict> conflicts=new ArrayList<>();List<String> ids=new ArrayList<>(original.keySet());
        for(int i=0;i<ids.size();i++)for(int j=i+1;j<ids.size();j++){String a=ids.get(i),b=ids.get(j);double[][] oa=source(original.get(a),width,height),ob=source(original.get(b),width,height);double denominator=Math.min(area(oa),area(ob));double oldRatio=intersection(oa,ob)/denominator,newRatio=intersection(source(selected.get(a),width,height),source(selected.get(b),width,height))/denominator;if(newRatio>Math.max(.5,oldRatio)+1e-6)conflicts.add(new Conflict(a,b,oldRatio,newRatio));}
        return new Page(conflicts.isEmpty()?selected:original,!conflicts.isEmpty(),conflicts);
    }
}
