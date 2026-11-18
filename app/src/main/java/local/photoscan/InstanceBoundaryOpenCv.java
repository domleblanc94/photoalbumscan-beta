package local.photoscan;

import androidx.annotation.DoNotInline;
import androidx.annotation.RequiresApi;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.List;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/** Frozen host float32 operations, including OpenCV 5's table-free remap, not chooser interpolation. */
final class InstanceBoundaryOpenCv implements InstanceBoundaryMath.Backend {
    /** Reads actual NCHW4x384 input absolutely; never changes its position or retains its RGB. */
    static float[] evidenceFromPacked(FloatBuffer packed,float[] edge96) {
        final int size=384,plane=size*size;
        if(packed==null||packed.limit()<4*plane||edge96==null||edge96.length!=96*96)
            throw new IllegalArgumentException("packed/evidence shape");
        for(float v:edge96)if(!Float.isFinite(v)||v<0||v>1)throw new IllegalArgumentException("edge range");
        Mat full=new Mat(size,size,CvType.CV_32FC3),rgb=new Mat(),dx=new Mat(),dy=new Mat();
        try {
            float[] row=new float[size*3];
            for(int y=0;y<size;y++){
                for(int x=0;x<size;x++)for(int c=0;c<3;c++){
                    float value=packed.get(c*plane+y*size+x);
                    if(!Float.isFinite(value)||value<0||value>1)throw new IllegalArgumentException("RGB range");
                    row[3*x+c]=value;
                }
                full.put(y,0,row);
            }
            Imgproc.resize(full,rgb,new Size(96,96),0,0,Imgproc.INTER_AREA);
            full.release();
            Imgproc.Sobel(rgb,dx,CvType.CV_32F,1,0,3,1,0,Core.BORDER_REFLECT_101);
            Imgproc.Sobel(rgb,dy,CvType.CV_32F,0,1,3,1,0,Core.BORDER_REFLECT_101);
            float[] xs=new float[96*96*3],ys=new float[xs.length],gradient=new float[96*96];
            dx.get(0,0,xs);dy.get(0,0,ys);
            double[] percentile=new double[gradient.length];
            for(int i=0;i<gradient.length;i++){
                int j=3*i;
                float a=xs[j]*xs[j]+ys[j]*ys[j];
                float b=xs[j+1]*xs[j+1]+ys[j+1]*ys[j+1];
                float c=xs[j+2]*xs[j+2]+ys[j+2]*ys[j+2];
                gradient[i]=(float)Math.sqrt(((a+b)+c)/3f);percentile[i]=gradient[i];
            }
            float scale=(float)Math.max(InstanceBoundaryMath.quantile(percentile,.95),1e-6);
            float[] evidence=new float[gradient.length];
            for(int i=0;i<evidence.length;i++)evidence[i]=.75f*edge96[i]+.25f*Math.min(1f,Math.max(0f,gradient[i]/scale));
            return evidence;
        } finally { full.release();rgb.release();dx.release();dy.release(); }
    }

    @Override public double peerIou(double[] a,double[] b){
        double[] af=InstanceBoundaryMath.toFloat(a),bf=InstanceBoundaryMath.toFloat(b);
        MatOfPoint2f am=quad(af),bm=quad(bf);Mat intersection=new Mat();
        try {
            double aa=Math.abs(InstanceBoundaryMath.signedArea(af)),ba=Math.abs(InstanceBoundaryMath.signedArea(bf));
            double shared=Math.min(Imgproc.intersectConvexConvex(am,bm,intersection),Math.min(aa,ba));
            return shared/Math.max(1e-9,aa+ba-shared);
        } finally {am.release();bm.release();intersection.release();}
    }

    @Override public double[] lineScores(List<InstanceBoundaryMath.Line> lines,double[] proposal,float[] evidence){
        if(android.os.Build.VERSION.SDK_INT<33)throw new IllegalStateException("C1 requires Android API 33");
        int count=lines.size(),samples=64;
            float[] maximum=new float[count*samples];
            for(int delta=-1;delta<=1;delta++){
                for(int i=0;i<count;i++){
                    InstanceBoundaryMath.Line line=lines.get(i);double tx=-line.ny,ty=line.nx;
                    double low=Double.POSITIVE_INFINITY,high=Double.NEGATIVE_INFINITY;
                    for(int j=0;j<4;j++){double p=proposal[2*j]*tx+proposal[2*j+1]*ty;low=Math.min(low,p);high=Math.max(high,p);}
                    for(int j=0;j<samples;j++){
                        double t=low+(high-low)*(j*(1.0/63));int index=i*samples+j;
                        float x=(float)(line.nx*line.rho+tx*t+line.nx*delta);
                        float y=(float)(line.ny*line.rho+ty*t+line.ny*delta);
                        maximum[index]=Math.max(maximum[index],hostRemapLinear(evidence,x,y));
                    }
                }
            }
            double[] scores=new double[count];float[] sorted=new float[samples];
            for(int i=0;i<count;i++){
                System.arraycopy(maximum,i*samples,sorted,0,samples);Arrays.sort(sorted);
                // Frozen NumPy 2.x retains float32 for quantile on float32 values.
                float difference=sorted[16]-sorted[15];float quartile=sorted[16]-difference*.25f;
                float weightedMean=.75f*(pairwise64(maximum,i*samples)/64f);
                scores[i]=weightedMean+.25f*quartile;
            }
            return scores;
    }
    /** Frozen host OpenCV 5 INTER_LINEAR uses full float32 fractions and fused lerps.
     * OpenCV 4.13's remap uses a 1/32 table and is numerically a different operation.
     * Constant-zero border applies independently to all four neighboring pixels.
     */
    @RequiresApi(33)
    static float hostRemapLinear(float[] image,float x,float y){
        if(x<=-1||y<=-1||x>=96||y>=96)return 0;
        int ix=(int)Math.floor(x),iy=(int)Math.floor(y);float fx=x-ix,fy=y-iy;
        float a=pixel(image,ix,iy),b=pixel(image,ix+1,iy),c=pixel(image,ix,iy+1),d=pixel(image,ix+1,iy+1);
        float top=ExactFloatFma.apply(fx,b-a,a),bottom=ExactFloatFma.apply(fx,d-c,c);
        return ExactFloatFma.apply(fy,bottom-top,top);
    }
    @RequiresApi(33)
    private static final class Api33Impl {
        @DoNotInline
        static float fma(float a,float b,float c){return Math.fma(a,b,c);}
    }
    private static float pixel(float[] image,int x,int y){return x<0||y<0||x>=96||y>=96?0:image[y*96+x];}
    private static float pairwise64(float[] values,int offset){
        float r0=values[offset],r1=values[offset+1],r2=values[offset+2],r3=values[offset+3];
        float r4=values[offset+4],r5=values[offset+5],r6=values[offset+6],r7=values[offset+7];
        for(int i=8;i<64;i+=8){r0+=values[offset+i];r1+=values[offset+i+1];r2+=values[offset+i+2];r3+=values[offset+i+3];
            r4+=values[offset+i+4];r5+=values[offset+i+5];r6+=values[offset+i+6];r7+=values[offset+i+7];}
        return ((r0+r1)+(r2+r3))+((r4+r5)+(r6+r7));
    }
    private static MatOfPoint2f quad(double[] q){return new MatOfPoint2f(new Point(q[0],q[1]),new Point(q[2],q[3]),new Point(q[4],q[5]),new Point(q[6],q[7]));}
}
