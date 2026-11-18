package local.photoscan;

import android.content.Context;

/** Explicit developer opt-in. Reading or changing this preference never reprocesses saved crops. */
final class InstanceBoundarySettings {
    static final String PREFERENCES="developer_boundary_experiment";
    static final String KEY="shipped_c1_enabled";
    private InstanceBoundarySettings(){}
    static boolean supportedApi(int sdk){return sdk>=33;}
    static String unavailableReason(){
        if(!supportedApi(android.os.Build.VERSION.SDK_INT))return "requires_android_api_33";
        return supported()?null:"incompatible_frozen_pipeline";
    }
    static boolean supported(){
        return supportedApi(android.os.Build.VERSION.SDK_INT) && BuildConfig.USE_OWN_PICTURE_MODELS && BuildConfig.OWN_PICTURE_SUPPLEMENT
                && "constrained_line".equals(BuildConfig.OWN_PICTURE_ROI_RECOVERY)
                && "source_edge_16".equals(BuildConfig.OWN_PICTURE_ROI_EDGE_RETRY)
                && "legacy".equals(BuildConfig.OWN_PICTURE_FIRST_HULL)
                && "mixed_trigger025_accept010".equals(BuildConfig.OWN_PICTURE_FALLBACK_POLICY)
                && "empty005_iou080_v2".equals(BuildConfig.OWN_PICTURE_VERIFIER_POLICY)
                && "selected_underhalf_two75_split_v2".equals(BuildConfig.OWN_PICTURE_LOCAL_POLICY)
                && BuildConfig.OWN_PICTURE_ROI_PADDING==.5
                && BuildConfig.OWN_PICTURE_HELPER_ROI_PADDING==.5
                && "l1-failed-helper-envelope-v1".equals(BuildConfig.OWN_L1_GEOMETRY_FALLBACK_POLICY)
                && "600f58506ffa4bdd32ec61bc697a36b4f68b7357e7a60170ebc87f222b67c1ea".equals(BuildConfig.PRINT_LOCATOR_SHA256)
                && "7af326cd0368735fdd09a71cbb295c9f896b4a4b4f64a00cea887b2dd7f23fa9".equals(BuildConfig.OWN_PICTURE_ROI_SHA256)
                && "c99d1dec299847f91767c05321af2c41c04f68a1928edfb1b2a05afc5fff1016".equals(BuildConfig.OWN_PICTURE_FALLBACK_SHA256);
    }
    static boolean enabled(Context context){return supported()&&context.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE).getBoolean(KEY,false);}
    static void set(Context context,boolean enabled){
        if(enabled&&!supported())throw new IllegalStateException("Boundary experiment unavailable in this build");
        context.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE).edit().putBoolean(KEY,enabled).apply();
    }
}
