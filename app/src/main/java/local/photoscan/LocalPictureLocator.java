package local.photoscan;

import android.graphics.Bitmap;
import android.util.Size;
import ai.onnxruntime.OrtException;
import java.io.InterruptedIOException;
import java.util.*;
import local.photoscan.PictureQuadFit.Vertex;

/** Bounded saved-page local locator. Enabled only by an explicit own-build policy. */
final class LocalPictureLocator {
    static final String POLICY = "local_selected_branch_nesting80_underhalf_two75_pad10_v2";
    record Window(double[] requested, int left, int top, int right, int bottom) {
        int width() { return right-left; }
        int height() { return bottom-top; }
    }
    record Local(int parent, int window, int rank, float confidence, Vertex[] quad,
                 int windowContacts, int artificialContacts) {}
    record Evidence(int parent, String trigger, boolean parentProtected,
                    List<Window> windows, List<Local> all, List<Local> selected) {}

    static int parent(List<PicturePageDecode.Proposal> proposals, Set<Integer> poor) {
        return parent(proposals,poor,Set.of());
    }

    static int parent(List<PicturePageDecode.Proposal> proposals,Set<Integer> poor,Set<Integer> undercovered) {
        int selected=-1;
        double largest=-1;
        for(int i=0;i<proposals.size();i++) {
            Vertex[] large=proposals.get(i).quad();
            if(!PictureContentDetector.eligibleFallbackProposal(large)) continue;
            double big=area(large);
            boolean suspect=poor.contains(i)||undercovered.contains(i);
            for(int j=0;j<proposals.size() && !suspect;j++) {
                Vertex[] small=proposals.get(j).quad();
                if(i==j || !PictureContentDetector.eligibleFallbackProposal(small))continue;
                double little=area(small);
                if(big<2*little)continue;
                double iou=PicturePageDecode.iou(large,small);
                double intersection=iou*(big+little)/(1+iou);
                suspect=intersection>=.80*little;
            }
            if(suspect && big>largest) {selected=i;largest=big;}
        }
        return selected;
    }

    static List<Window> windows(Vertex[] parent, Size source, int pw, int ph) {
        double left=Double.POSITIVE_INFINITY,top=left,right=Double.NEGATIVE_INFINITY,bottom=right;
        for(Vertex p:parent) {
            left=Math.min(left,p.x());right=Math.max(right,p.x());
            top=Math.min(top,p.y());bottom=Math.max(bottom,p.y());
        }
        double mx=.10*(right-left),my=.10*(bottom-top);
        left=Math.max(0,left-mx);right=Math.min(source.getWidth()-1,right+mx);
        top=Math.max(0,top-my);bottom=Math.min(source.getHeight()-1,bottom+my);
        if(!(right>left && bottom>top))return List.of();
        List<Window> windows=new ArrayList<>();
        boolean horizontal=right-left>=bottom-top;
        double span=.75*(horizontal ? right-left : bottom-top);
        for(int i=0;i<2;i++) {
            double l=left,t=top,r=right,b=bottom;
            if(horizontal) {if(i==0)r=l+span;else l=r-span;}
            else {if(i==0)b=t+span;else t=b-span;}
            int x0=Math.max(0,(int)Math.floor((l+.5)*pw/source.getWidth()));
            int x1=Math.min(pw,(int)Math.ceil((r+.5)*pw/source.getWidth()));
            int y0=Math.max(0,(int)Math.floor((t+.5)*ph/source.getHeight()));
            int y1=Math.min(ph,(int)Math.ceil((b+.5)*ph/source.getHeight()));
            if(x1-x0>=2 && y1-y0>=2)windows.add(new Window(new double[]{l,t,r,b},x0,y0,x1,y1));
        }
        return windows;
    }

    static Vertex[] map(float[] normalized, Window window, Size source, int pw, int ph) {
        Vertex[] mapped=new Vertex[4];
        for(int i=0;i<4;i++) {
            double x=(window.left()+normalized[2*i]*(double)window.width())*source.getWidth()/pw-.5;
            double y=(window.top()+normalized[2*i+1]*(double)window.height())*source.getHeight()/ph-.5;
            mapped[i]=new Vertex((float)x,(float)y);
        }
        return mapped;
    }

    static int contacts(float[] quad) {
        int contacts=0;
        for(int i=0;i<8;i+=2) {
            if(quad[i]<=1e-6f)contacts|=1;
            if(quad[i+1]<=1e-6f)contacts|=2;
            if(quad[i]>=1-1e-6f)contacts|=4;
            if(quad[i+1]>=1-1e-6f)contacts|=8;
        }
        return contacts;
    }

    static Evidence detect(QuadModel model, Bitmap preview, Size source,
                           List<PicturePageDecode.Proposal> proposals, Set<Integer> poor)
            throws OrtException, InterruptedIOException {
        return detect(crop -> model.detect(crop,true),preview,source,proposals,poor,Set.of());
    }

    static Evidence detect(QuadModel model,Bitmap preview,Size source,List<PicturePageDecode.Proposal> proposals,
                           Set<Integer> poor,Set<Integer> undercovered) throws OrtException,InterruptedIOException {
        return detect(crop->model.detect(crop,true),preview,source,proposals,poor,undercovered);
    }

    interface Predictor {
        List<QuadModel.Prediction> detect(Bitmap crop) throws OrtException;
    }

    static Evidence detect(Predictor predictor, Bitmap preview, Size source,
                           List<PicturePageDecode.Proposal> proposals, Set<Integer> poor)
            throws OrtException, InterruptedIOException {
        return detect(predictor,preview,source,proposals,poor,Set.of());
    }

    static Evidence detect(Predictor predictor,Bitmap preview,Size source,List<PicturePageDecode.Proposal> proposals,
                           Set<Integer> poor,Set<Integer> undercovered) throws OrtException,InterruptedIOException {
        int parent=parent(proposals,poor,undercovered);
        if(parent<0)return new Evidence(-1,"",false,List.of(),List.of(),List.of());
        var windows=windows(proposals.get(parent).quad(),source,preview.getWidth(),preview.getHeight());
        List<Local> all=new ArrayList<>();
        for(int i=0;i<windows.size();i++) {
            requireActive();
            Window window=windows.get(i);
            Bitmap crop=Bitmap.createBitmap(preview,window.left(),window.top(),window.width(),window.height());
            try {
                var predictions=predictor.detect(crop);
                for(int rank=0;rank<predictions.size();rank++) {
                    var prediction=predictions.get(rank);
                    int edges=contacts(prediction.quad());
                    int real=(window.left()==0?1:0)|(window.top()==0?2:0)
                            |(window.right()==preview.getWidth()?4:0)|(window.bottom()==preview.getHeight()?8:0);
                    all.add(new Local(parent,i,rank,prediction.confidence(),
                            map(prediction.quad(),window,source,preview.getWidth(),preview.getHeight()),edges,edges&~real));
                }
            } finally {
                if(crop!=preview)crop.recycle();
            }
        }
        requireActive();
        String trigger=undercovered.contains(parent)?"reviewed_fallback_under_half"
                :poor.contains(parent)?"poor_mask_agreement":"strong_raw_nesting";
        return new Evidence(parent,trigger,undercovered.contains(parent),
                windows,all,select(all));
    }

    static boolean undercovered(Vertex[] selected,Vertex[] proposal,boolean acceptedFallback,boolean reviewed) {
        return acceptedFallback && reviewed && selected!=null && proposal!=null
                && area(selected)<.5*area(proposal);
    }

    static List<Local> select(List<Local> all) {
        List<Local> sorted=new ArrayList<>(all),selected=new ArrayList<>();
        sorted.sort(Comparator.comparingDouble((Local p)->p.confidence()).reversed()
                .thenComparingInt(Local::window).thenComparingInt(Local::rank));
        for(Local p:sorted) {
            if(!PictureContentDetector.eligibleFallbackProposal(p.quad()))continue;
            boolean duplicate=false;
            for(Local previous:selected) if(PicturePageDecode.iou(p.quad(),previous.quad())>.27) {duplicate=true;break;}
            if(!duplicate)selected.add(p);
            if(selected.size()==6)break;
        }
        return selected;
    }

    static void requireActive() throws InterruptedIOException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Local locator interrupted");
    }

    static double area(Vertex[] points) {
        double area=0;
        for(int i=0;i<4;i++) {
            Vertex a=points[i],b=points[(i+1)%4];
            area+=(double)a.x()*b.y()-(double)b.x()*a.y();
        }
        return Math.abs(area)*.5;
    }
}
