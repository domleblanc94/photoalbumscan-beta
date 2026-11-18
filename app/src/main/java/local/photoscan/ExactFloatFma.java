package local.photoscan;

import androidx.annotation.DoNotInline;
import androidx.annotation.RequiresApi;

/** Exact binary32 FMA fast path; ambiguous double-rounding boundaries use platform FMA. */
@RequiresApi(33)
final class ExactFloatFma {
    private ExactFloatFma() {}

    static float apply(float a,float b,float c) {
        if(!Float.isFinite(a)||!Float.isFinite(b)||!Float.isFinite(c)){
            return Api33Impl.fma(a,b,c);
        }
        // Every finite binary32 product is exactly representable in binary64.
        double rounded=(double)a*(double)b+(double)c;
        if(rounded==0){return Api33Impl.fma(a,b,c);}
        float candidate=(float)rounded;
        if(!Float.isFinite(candidate)){return Api33Impl.fma(a,b,c);}
        if((double)candidate==rounded)return candidate;
        // Choose the adjacent float toward rounded, including asymmetric power-of-two spacing.
        float neighbor=Math.nextAfter(candidate,rounded);
        if(!Float.isFinite(neighbor)){return Api33Impl.fma(a,b,c);}
        // Two adjacent finite floats' sum and midpoint are exactly representable in double.
        double midpoint=((double)candidate+(double)neighbor)*.5;
        if(rounded==midpoint){return Api33Impl.fma(a,b,c);}
        return candidate;
    }

    @RequiresApi(33)
    private static final class Api33Impl {
        @DoNotInline
        static float fma(float a,float b,float c){return Math.fma(a,b,c);}
    }
}
