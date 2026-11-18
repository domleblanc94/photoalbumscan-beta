package local.photoscan;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import android.content.Context;
import android.util.Size;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import local.photoscan.PictureQuadFit.Vertex;
import org.opencv.android.OpenCVLoader;

/** Saved-page-only inner picture detector. The camera's live guide is independent. */
final class PictureContentDetector implements AutoCloseable {
    static final String PAGE_ASSET = "picture-floor060.onnx";
    static final String ROI_ASSET = "picture-roi040.onnx";
    static final String OWN_ROI_ASSET = "picture-own-roi.onnx";
    static final String OWN_FALLBACK_ROI_ASSET = "picture-own-fallback-roi.onnx";
    static final String CONSTRAINED_RECOVERY = "constrained_line";
    static final String SOURCE_EDGE_RETRY = "source_edge_16";
    static final String LEGACY_FIRST_HULL = "legacy";
    static final String OPENCV_FIRST_HULL = "opencv_float";
    static final String MIXED_FALLBACK = "mixed_area_025";
    static final String MIXED_FALLBACK_V2 = "mixed_trigger025_accept010";
    static final String SEMANTIC_VERIFIER = "empty005_v1";
    static final String AGREEMENT_VERIFIER = "empty005_iou080_v2";
    static final String LOCAL_SPLIT = "selected_underhalf_two75_split_v2";
    private static final double PRIMARY_MIN_AREA_RATIO = .25;
    private static final int PAGE = PictureModelInput.PAGE;
    private static final int ROI = PictureModelInput.ROI;
    private static final String MASK = "mask_logits";
    private static final String EDGE = "boundary_logits";
    private static final String CENTER = "center_logits";
    private static final String VECTORS = "instance_vectors";

    static final class Candidate {
        final float[] corners;
        final boolean reviewRequired;
        final String reason;
        final FallbackEvidence fallback;
        final String disposition;
        final VerifierEvidence verifier;
        final Lineage lineage;
        final CropReviewPolicy.FitEvidence fitEvidence;
        final BoundaryEvidence boundaryEvidence;
        final InstanceBoundaryIntegration.Effective instanceBoundary;

        Candidate(float[] corners, String reason) {
            this(corners, reason, null);
        }

        Candidate(float[] corners, String reason, FallbackEvidence fallback) {
            this(corners, reason, fallback, corners == null ? "unresolved" : "crop", null);
        }

        Candidate(float[] corners, String reason, FallbackEvidence fallback, String disposition,
                  VerifierEvidence verifier) {
            this(corners,reason,fallback,disposition,verifier,null);
        }

        Candidate(float[] corners,String reason,FallbackEvidence fallback,String disposition,
                  VerifierEvidence verifier,Lineage lineage) {
            this(corners,reason,fallback,disposition,verifier,lineage,null);
        }

        Candidate(float[] corners,String reason,FallbackEvidence fallback,String disposition,
                  VerifierEvidence verifier,Lineage lineage,CropReviewPolicy.FitEvidence fitEvidence) {
            this(corners,reason,fallback,disposition,verifier,lineage,fitEvidence,null);
        }
        Candidate(float[] corners,String reason,FallbackEvidence fallback,String disposition,
                  VerifierEvidence verifier,Lineage lineage,CropReviewPolicy.FitEvidence fitEvidence,BoundaryEvidence boundaryEvidence) {
            this(corners,reason,fallback,disposition,verifier,lineage,fitEvidence,boundaryEvidence,null);
        }
        Candidate(float[] corners,String reason,FallbackEvidence fallback,String disposition,
                  VerifierEvidence verifier,Lineage lineage,CropReviewPolicy.FitEvidence fitEvidence,BoundaryEvidence boundaryEvidence,
                  InstanceBoundaryIntegration.Effective instanceBoundary) {
            this.instanceBoundary=instanceBoundary;
            this.corners = corners;
            this.reason = reason;
            this.fallback = fallback;
            this.disposition = disposition;
            this.verifier = verifier;
            this.lineage = lineage;
            this.fitEvidence = fitEvidence;
            this.boundaryEvidence = boundaryEvidence;
            this.reviewRequired = corners == null || !reason.isEmpty();
        }

        boolean omittedFromPhotos() {
            return "verified_background".equals(disposition) || "suppressed_parent".equals(disposition)
                    || "deduplicated_local".equals(disposition) || "rejected_local".equals(disposition);
        }
        float[] orderCorners() { return instanceBoundary!=null?instanceBoundary.originalCorners():boundaryEvidence==null?corners:boundaryEvidence.originalCorners(); }
    }

    record FallbackEvidence(String primaryReason, String fallbackReason, boolean overlaps) {}
    record FirstMaskEvidence(boolean emptyMask, int activePixels, double maximum, boolean finite) {}
    record VerifierEvidence(String action, String primaryReason, String helperReason,
                            FirstMaskEvidence firstMask, float[] primaryCorners,
                            boolean geometryDisagreement,Double quadIou,boolean helperGeometryInvalid) {}
    record Lineage(String id,String origin,Integer parent,Integer window,Integer rank,
                   List<String> supersededBy,float[] priorCorners) {}
    record LocalEvidence(LocalPictureLocator.Evidence locator,List<Refined> fits,String error) {}
    record ReviewReference(float[] mask,float[] edge,double[] transform) {}
    record BoundaryEvidence(String policy,String reportedReason,String decision,boolean guardPassed,boolean helperExecuted,boolean helperQuadNull,boolean accepted,
                            boolean pageRollback,double rawCoverage,double areaRatio,double minimumDistance,double originalRetention,
                            float[] originalCorners,float[] finalCorners,CropReviewPolicy.FitEvidence originalFit,
                            VerifierEvidence originalVerifier,String fitScope) {}
    record BoundarySummary(String policy,int ordinaryCount,int finalCount,boolean pageRollback,
                           List<L1GeometryFallback.Conflict> conflicts) {}

    static final class Result {
        final List<Candidate> candidates;
        final boolean reviewRequired;
        final boolean overflow;
        final LocalEvidence local;
        final int reviewSourceWidth;
        final int reviewSourceHeight;
        final long reviewImageMillis;
        final BoundarySummary boundarySummary;
        private final Map<Candidate, CropReviewImageEvidence.Alternate> alternateReviewEvidence;
        private final List<PicturePageDecode.Proposal> reviewProposals;

        Result(List<Candidate> candidates, boolean overflow) {
            this(candidates,overflow,null);
        }
        Result(List<Candidate> candidates,boolean overflow,LocalEvidence local) {
            this(candidates, overflow, local, 0, 0, 0, Map.of(), List.of());
        }
        Result(List<Candidate> candidates, boolean overflow, LocalEvidence local,
               int reviewSourceWidth, int reviewSourceHeight, long reviewImageMillis,
               Map<Candidate, CropReviewImageEvidence.Alternate> alternateReviewEvidence,
               List<PicturePageDecode.Proposal> reviewProposals) {
            this(candidates,overflow,local,reviewSourceWidth,reviewSourceHeight,reviewImageMillis,alternateReviewEvidence,reviewProposals,null);
        }
        Result(List<Candidate> candidates, boolean overflow, LocalEvidence local,
               int reviewSourceWidth,int reviewSourceHeight,long reviewImageMillis,
               Map<Candidate,CropReviewImageEvidence.Alternate> alternateReviewEvidence,
               List<PicturePageDecode.Proposal> reviewProposals,BoundarySummary boundarySummary) {
            this.candidates = Collections.unmodifiableList(candidates);
            this.overflow = overflow;
            this.local = local;
            this.reviewSourceWidth = reviewSourceWidth;
            this.reviewSourceHeight = reviewSourceHeight;
            this.reviewImageMillis = reviewImageMillis;
            this.alternateReviewEvidence = alternateReviewEvidence;
            this.reviewProposals = reviewProposals;
            this.boundarySummary=boundarySummary;
            this.reviewRequired = overflow || candidates.isEmpty()
                    || candidates.stream().anyMatch(c -> c.reviewRequired);
        }
        CropReviewImageEvidence.Alternate alternateReviewEvidence(Candidate candidate) {
            return alternateReviewEvidence.get(candidate);
        }
        String cropReviewReason(Candidate candidate, List<Candidate> retained) {
            String reason = CropReviewPolicy.reason(candidate, retained, reviewSourceWidth, reviewSourceHeight,
                    alternateReviewEvidence(candidate));
            if (!reason.isEmpty() || !BuildConfig.EXPERIMENTAL_CROP_REVIEW) return reason;
            var score = scalarReview(candidate, retained);
            return score != null && score.warns() ? CropReviewPolicy.POSSIBLE_PICTURE_CUT : "";
        }
        CropReviewScalar.Score scalarReview(Candidate candidate, List<Candidate> retained) {
            if (!BuildConfig.EXPERIMENTAL_CROP_REVIEW || candidate.lineage == null
                    || reviewSourceWidth <= 0 || reviewSourceHeight <= 0) return null;
            Integer parent = "local".equals(candidate.lineage.origin())
                    ? candidate.lineage.parent() : Integer.valueOf(candidates.indexOf(candidate));
            if (parent == null || parent < 0 || parent >= reviewProposals.size()) return null;
            Vertex[] sourceQuad = reviewProposals.get(parent).quad();
            if (sourceQuad == null || sourceQuad.length != 4) return null;
            double[] quad = new double[8];
            for (int i = 0; i < sourceQuad.length; i++) {
                quad[2 * i] = (sourceQuad[i].x() + .5) / reviewSourceWidth;
                quad[2 * i + 1] = (sourceQuad[i].y() + .5) / reviewSourceHeight;
            }
            return CropReviewScalar.score(candidate, retained, quad, reviewSourceWidth, reviewSourceHeight);
        }
    }

    private final OrtEnvironment environment = OfflineOrt.environment();
    private final OrtSession pageSession;
    private final OwnPictureLocator ownLocator;
    private final OrtSession roiSession;
    private final Context fallbackContext;
    private final String fallbackPolicy;
    private final boolean verifyPrimary;
    private final boolean warnLowAgreement;
    private final boolean localSplits;
    private OrtSession fallbackSession;
    private final double roiPadding;
    private final double helperRoiPadding;
    record RefinerCall(String stage, String lineage, String role, String modelSha256,
                       double requestedPadding, boolean retry, double[] sourceToInput,
                       float cueX, float cueY, String inputSha256) {}
    private final List<RefinerCall> refinerCalls = new ArrayList<>();
    private boolean refinerTensorHashes;
    private boolean instanceChooserEnabled;
    private String instanceChooserUnavailable;
    String instanceChooserUnavailable(){return instanceChooserUnavailable;}
    private InstanceBoundaryIntegration instanceChooser;
    private List<InstanceBoundaryIntegration.Effective> instanceChoices=List.of();
    void enableInstanceChooser(boolean enabled) {
        if(enabled&&!InstanceBoundarySettings.supportedApi(android.os.Build.VERSION.SDK_INT))
            throw new IllegalArgumentException("C1 requires Android API 33 or newer");
        if(enabled && (!BuildConfig.USE_OWN_PICTURE_MODELS || ownLocator==null || !localSplits || !verifyPrimary
                || !warnLowAgreement || !MIXED_FALLBACK_V2.equals(fallbackPolicy) || !recoverConstrained
                || !retrySourceEdges || nativeFirstHull || roiPadding!=.5 || helperRoiPadding!=.5
                || !L1GeometryFallback.POLICY.equals(BuildConfig.OWN_L1_GEOMETRY_FALLBACK_POLICY)
                || !"7af326cd0368735fdd09a71cbb295c9f896b4a4b4f64a00cea887b2dd7f23fa9".equals(BuildConfig.OWN_PICTURE_ROI_SHA256)
                || !"c99d1dec299847f91767c05321af2c41c04f68a1928edfb1b2a05afc5fff1016".equals(BuildConfig.OWN_PICTURE_FALLBACK_SHA256)
                || !"600f58506ffa4bdd32ec61bc697a36b4f68b7357e7a60170ebc87f222b67c1ea".equals(BuildConfig.PRINT_LOCATOR_SHA256)))
            throw new IllegalArgumentException("C1 requires frozen shipped L1/R3/c99/R8 .50/.50");
        instanceChooserEnabled=enabled;
        instanceChooser=enabled?new InstanceBoundaryIntegration():null;
    }
    List<InstanceBoundaryIntegration.Effective> instanceChoices(){return instanceChoices;}
    InstanceBoundaryIntegration.Timing instanceTiming(){return instanceChooserEnabled?instanceChooser.timing():null;}
    private Refined captureInstance(RefinerCall call,PicturePageDecode.Proposal proposal,float[] mask,float[] edge,Refined returned){
        if(instanceChooserEnabled&&instanceChooserUnavailable==null){
            try { instanceChooser.capture(call,proposal,roiInput,mask,edge,returned); }
            catch(CancellationException cancelled){throw cancelled;}
            catch(RuntimeException unavailable){
                if(Thread.currentThread().isInterrupted())throw new CancellationException("C1 interrupted");
                instanceChooserUnavailable=unavailable.getClass().getSimpleName()+": "+unavailable.getMessage();
                instanceChooser.clear();
                android.util.Log.w("PhotoScanC1","Optional evidence unavailable; ordinary page retained",unavailable);
            }
        }
        return returned;
    }
    void enableRefinerTensorHashes(boolean enabled) { refinerTensorHashes = enabled; }
    List<RefinerCall> refinerCalls() { return List.copyOf(refinerCalls); }

    private String refinerInputSha256() {
        if (!refinerTensorHashes) return null; // Explicit diagnostic pass; excluded from warmed timing.
        try {
            var bytes = java.nio.ByteBuffer.allocate(roiInput.capacity()*Float.BYTES)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (int i=0;i<roiInput.capacity();i++) bytes.putFloat(roiInput.get(i));
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes.array());
            var hex = new StringBuilder(hash.length*2);
            for (byte value:hash) {
                hex.append(Character.forDigit((value>>>4)&15,16));
                hex.append(Character.forDigit(value&15,16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    private final boolean recoverConstrained;
    private final boolean retrySourceEdges;
    private final boolean nativeFirstHull;
    private final FloatBuffer pageInput;
    private final FloatBuffer roiInput = PictureModelInput.buffer(ROI, 4);

    PictureContentDetector(Context context) throws IOException, OrtException {
        this(context, BuildConfig.USE_OWN_PICTURE_MODELS, null, BuildConfig.OWN_PICTURE_SUPPLEMENT,
                BuildConfig.USE_OWN_PICTURE_MODELS ? BuildConfig.OWN_PICTURE_ROI_PADDING
                        : PictureModelInput.DEFAULT_ROI_PADDING,
                BuildConfig.USE_OWN_PICTURE_MODELS ? BuildConfig.OWN_PICTURE_HELPER_ROI_PADDING
                        : PictureModelInput.DEFAULT_ROI_PADDING,
                BuildConfig.USE_OWN_PICTURE_MODELS ? BuildConfig.OWN_PICTURE_ROI_RECOVERY : "none",
                BuildConfig.USE_OWN_PICTURE_MODELS ? BuildConfig.OWN_PICTURE_ROI_EDGE_RETRY : "none",
                BuildConfig.OWN_PICTURE_FIRST_HULL, BuildConfig.OWN_PICTURE_FALLBACK_POLICY,
                BuildConfig.OWN_PICTURE_VERIFIER_POLICY,BuildConfig.OWN_PICTURE_LOCAL_POLICY);
    }

    /** Experimental route only. The caller supplies the selected, verified ROI checkpoint. */
    static PictureContentDetector ownCandidate(Context context, File roiWeights, boolean supplement, double roiPadding,
                                                String roiRecovery, String roiEdgeRetry, String firstHull)
            throws IOException, OrtException {
        return new PictureContentDetector(context, true, Objects.requireNonNull(roiWeights), supplement, roiPadding, roiPadding,
                roiRecovery, roiEdgeRetry, firstHull, "none", "none", "none");
    }

    private PictureContentDetector(Context context, boolean own, File roiWeights, boolean supplement, double roiPadding, double helperRoiPadding,
                                   String roiRecovery, String roiEdgeRetry, String firstHull, String fallbackPolicy,
                                   String verifierPolicy,String localPolicy)
            throws IOException, OrtException {
        PictureModelInput.requireRoiPadding(roiPadding);
        this.roiPadding = roiPadding;
        PictureModelInput.requireRoiPadding(helperRoiPadding);
        this.helperRoiPadding = helperRoiPadding;
        if (roiPadding == .75 && (helperRoiPadding != .50
                || !"none".equals(BuildConfig.OWN_L1_GEOMETRY_FALLBACK_POLICY))) {
            throw new IllegalArgumentException("R9 requires .75 primary/.50 helper with R8 disabled.");
        }
        if (!"none".equals(roiRecovery) && !(own && CONSTRAINED_RECOVERY.equals(roiRecovery))) {
            throw new IllegalArgumentException("Unsupported ROI recovery policy.");
        }
        recoverConstrained = own && CONSTRAINED_RECOVERY.equals(roiRecovery);
        retrySourceEdges = own && SOURCE_EDGE_RETRY.equals(roiEdgeRetry);
        if (!"none".equals(roiEdgeRetry) && !(retrySourceEdges && recoverConstrained && (roiPadding == .50 || roiPadding == .75))) {
            throw new IllegalArgumentException("Unsupported source-edge retry policy.");
        }
        nativeFirstHull = own && recoverConstrained && OPENCV_FIRST_HULL.equals(firstHull);
        if (!LEGACY_FIRST_HULL.equals(firstHull) && !nativeFirstHull) {
            throw new IllegalArgumentException("Unsupported first-pass hull policy.");
        }
        boolean useFallback = own && supplement && retrySourceEdges && recoverConstrained
                && (roiPadding == .50 || roiPadding == .75) && helperRoiPadding == .50 && LEGACY_FIRST_HULL.equals(firstHull)
                && (MIXED_FALLBACK.equals(fallbackPolicy) || MIXED_FALLBACK_V2.equals(fallbackPolicy));
        if (!"none".equals(fallbackPolicy) && !useFallback) {
            throw new IllegalArgumentException("Unsupported fallback ROI policy.");
        }
        fallbackContext = useFallback ? context.getApplicationContext() : null;
        this.fallbackPolicy = fallbackPolicy;
        verifyPrimary = useFallback && MIXED_FALLBACK_V2.equals(fallbackPolicy)
                && (SEMANTIC_VERIFIER.equals(verifierPolicy) || AGREEMENT_VERIFIER.equals(verifierPolicy));
        warnLowAgreement=AGREEMENT_VERIFIER.equals(verifierPolicy);
        if (!"none".equals(verifierPolicy) && !verifyPrimary) {
            throw new IllegalArgumentException("Unsupported primary verifier policy.");
        }
        localSplits=verifyPrimary && LOCAL_SPLIT.equals(localPolicy);
        if(!"none".equals(localPolicy) && !localSplits)throw new IllegalArgumentException("Unsupported local proposal policy.");
        if (!OpenCVLoader.initLocal()) throw new IOException("Could not load picture crop geometry.");
        pageInput = own ? null : PictureModelInput.buffer(PAGE, 3);
        ownLocator = own ? new OwnPictureLocator(context, supplement) : null;
        pageSession = own ? null : open(context.getAssets().open(PAGE_ASSET));
        try {
            roiSession = open(roiWeights == null
                    ? context.getAssets().open(own ? OWN_ROI_ASSET : ROI_ASSET)
                    : new FileInputStream(roiWeights));
        } catch (IOException | OrtException | RuntimeException | LinkageError | OutOfMemoryError failure) {
            try {
                closeLocator();
            } catch (OrtException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private OrtSession open(InputStream stream) throws IOException, OrtException {
        try (InputStream weights = stream;
             OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            // The independent live model can also run. Avoid another spinning pool.
            options.setInterOpNumThreads(1);
            options.setIntraOpNumThreads(1);
            options.addConfigEntry("session.intra_op.allow_spinning", "0");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[32 * 1024];
            for (int count; (count = weights.read(buffer)) != -1;) bytes.write(buffer, 0, count);
            return environment.createSession(bytes.toByteArray(), options);
        }
    }

    synchronized Result detect(File original) throws IOException, OrtException {
        return detect(original, null);
    }

    synchronized Result detect(File original, Consumer<PicturePageDecode.Page> locatorEvidence)
            throws IOException, OrtException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Picture processing interrupted.");
        Size source = PictureModelInput.uprightSize(original);
        if(localSplits) {
            try(OwnPictureLocator.Input input=ownLocator.open(original,source)) {
                return detectPage(original,source,input.page,input,locatorEvidence);
            }
        }
        var page = ownLocator == null ? coarse(original, source) : ownLocator.detect(original, source);
        return detectPage(original,source,page,null,locatorEvidence);
    }

    private Result detectPage(File original,Size source,PicturePageDecode.Page page,OwnPictureLocator.Input retained,
                              Consumer<PicturePageDecode.Page> locatorEvidence) throws IOException,OrtException {
        try { return detectPageInternal(original,source,page,retained,locatorEvidence); }
        finally { if(instanceChooser!=null)instanceChooser.clear(); }
    }

    private Result detectPageInternal(File original,Size source,PicturePageDecode.Page page,OwnPictureLocator.Input retained,
                              Consumer<PicturePageDecode.Page> locatorEvidence) throws IOException,OrtException {
        refinerCalls.clear();
        instanceChoices=List.of();
        instanceChooserUnavailable=null;
        if(instanceChooserEnabled)instanceChooser.resetPage();
        List<Refined> primary = new ArrayList<>();
        List<Refined> fallback = fallbackContext == null ? null : new ArrayList<>();
        List<Refined> verification = verifyPrimary ? new ArrayList<>() : null;
        List<Vertex[]> finalQuads = fallback == null ? null : new ArrayList<>();
        FallbackAttempts fallbackAttempts = fallback == null ? null : new FallbackAttempts();
        PictureModelInput.Rgb detail = null;
        for (var p : page.proposals()) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Picture processing interrupted.");
            Refined first;
            Refined second = null;
            Refined checked = null;
            if (p.quad() == null) {
                first = new Refined(null, "invalid_upstream_proposal");
            } else {
                if (detail == null) detail = PictureModelInput.previewRgb(original, 2000);
                double[] transform = PictureModelInput.roiTransform(source, p.quad(), roiPadding);
                first = refine(detail, source, p, transform, roiSession, nativeFirstHull, roiPadding, "global_primary", "global:"+primary.size());
                double[] helperTransform = PictureModelInput.roiTransform(source, p.quad(), helperRoiPadding);
                if (fallbackAttempts != null && helperTransform != null) {
                    PictureModelInput.Rgb sharedDetail = detail;
                    second = fallbackAttempts.runIfNeeded(first, p.quad(),
                            () -> refine(sharedDetail, source, p, helperTransform, fallbackSession(), true, helperRoiPadding, "global_fallback", "global:"+primary.size()));
                    if (verifyPrimary && second == null && first.quad() != null
                            && eligibleFallbackProposal(p.quad()) && meetsPrimaryArea(first.quad(),p.quad())) {
                        checked = fallbackAttempts.run(
                                () -> refine(sharedDetail, source, p, helperTransform, fallbackSession(), true, helperRoiPadding, "global_verifier", "global:"+primary.size()));
                    }
                }
            }
            primary.add(first);
            if (verification != null) verification.add(checked);
            if (fallback != null) {
                fallback.add(second);
                finalQuads.add(second == null ? semanticBackground(checked) ? null : first.quad()
                        : acceptedFallback(second.quad(), p.quad(), source.getWidth(), source.getHeight(), fallbackPolicy));
            }
        }
        RoiObservation.globalReturns(primary,fallback,verification);
        boolean[] primaryOverlaps = PicturePageDecode.overlaps(primary.stream().map(Refined::quad)
                .collect(java.util.stream.Collectors.toList()));
        boolean[] finalOverlaps = finalQuads == null ? null : PicturePageDecode.overlaps(finalQuads);
        List<Candidate> results = new ArrayList<>();
        for (int i = 0; i < primary.size(); i++) {
            Refined first = primary.get(i);
            String primaryReason = primaryOverlaps[i] ? "duplicate_or_merged_geometry" : first.reason();
            Refined second = fallback == null ? null : fallback.get(i);
            if (second == null) {
                Refined checked = verification == null ? null : verification.get(i);
                float[] originalCorners = PicturePageDecode.canvasCorners(first.quad(),source);
                if (checked == null) {
                    results.add(new Candidate(originalCorners,primaryReason,null,
                            originalCorners == null ? "unresolved" : "crop",null,null,first.fitEvidence()));
                } else {
                    boolean veto = semanticBackground(checked);
                    Double agreement=warnLowAgreement?agreement(first.quad(),checked.quad(),source.getWidth(),source.getHeight()):null;
                    boolean disagreement=agreement!=null && agreement<.80;
                    boolean invalidHelper=warnLowAgreement && checked.quad()!=null
                            && !PictureQuadFit.validSourceQuad(checked.quad(),source.getWidth(),source.getHeight());
                    boolean uncertain = !veto && (checked.quad() == null || disagreement || invalidHelper);
                    String action = veto ? "verified_background" : uncertain ? "inconclusive" : "keep_primary";
                    String reason = veto ? "verified_background"
                            : uncertain && primaryReason.isEmpty() ? "own_verifier_uncertain" : primaryReason;
                    var evidence = new VerifierEvidence(action,primaryReason,checked.reason(),checked.firstMask(),
                            originalCorners,disagreement,agreement,invalidHelper);
                    results.add(new Candidate(veto ? null : originalCorners,reason,null,
                            veto ? "verified_background" : "crop",evidence,null,first.fitEvidence()));
                }
            } else {
                Vertex[] quad = finalQuads.get(i);
                var evidence = new FallbackEvidence(primaryReason, second.reason(), finalOverlaps[i]);
                results.add(new Candidate(PicturePageDecode.canvasCorners(quad, source),
                        quad == null ? "own_fallback_rejected" : "own_mixed_fallback", evidence,
                        quad == null ? "unresolved" : "crop",null,null,second.fitEvidence()));
            }
        }
        for(int i=0;i<results.size();i++) {
            Candidate c=results.get(i);
            results.set(i,new Candidate(c.corners,c.reason,c.fallback,c.disposition,c.verifier,
                    new Lineage("global:"+i,"global",null,null,null,List.of(),null),c.fitEvidence));
        }
        if(instanceChooserEnabled&&instanceChooserUnavailable==null){
            // Commit only a complete page. A late invalid-evidence failure cannot leave mixed branches.
            var proposedResults=new ArrayList<>(results);
            try {
                var choices=instanceChooser.apply(proposedResults,page.proposals(),primary,fallback,verification,finalQuads,primaryOverlaps,source);
                results.clear();results.addAll(proposedResults);instanceChoices=choices;
            }catch(CancellationException cancelled){throw cancelled;}
            catch(RuntimeException unavailable){
                if(Thread.currentThread().isInterrupted())throw new CancellationException("C1 interrupted");
                instanceChooserUnavailable=unavailable.getClass().getSimpleName()+": "+unavailable.getMessage();
                android.util.Log.w("PhotoScanC1","Optional chooser unavailable; ordinary page retained",unavailable);
            }finally { instanceChooser.clear(); }
        }
        boolean effectiveInstances=instanceChooserEnabled&&instanceChooserUnavailable==null;
        LocalEvidence local=null;
        if(localSplits && retained!=null) {
            var poor=new java.util.HashSet<Integer>();
            var undercovered=new java.util.HashSet<Integer>();
            List<Refined> selected=new ArrayList<>();
            for(int i=0;i<primary.size();i++) {
                Refined second=fallback.get(i);
                var branch=effectiveInstances?instanceChoices.get(i).branch():selectedForLocal(primary.get(i),second,finalQuads.get(i),primaryOverlaps[i]);
                Refined chosen=branch.refined();
                selected.add(chosen);
                if(!results.get(i).omittedFromPhotos() && chosen.fitReasons().contains("poor_mask_agreement"))poor.add(i);
                if(LocalPictureLocator.undercovered(chosen.quad(),page.proposals().get(i).quad(),
                        branch.acceptedFallback(),results.get(i).reviewRequired))undercovered.add(i);
            }
            try {
                var extra=ownLocator.local(retained,source,poor,undercovered);
                List<Refined> fits=new ArrayList<>();
                List<LocalCropArbitration.Item> items=new ArrayList<>();
                for(int i=0;i<results.size();i++) {
                    Candidate c=results.get(i);
                    Refined chosen=selected.get(i);
                    boolean reviewedBeforeVerifier=(effectiveInstances?instanceChoices.get(i).branch():selectedForLocal(primary.get(i),fallback.get(i),finalQuads.get(i),
                            primaryOverlaps[i])).reviewedBeforeVerifier();
                    if(extra.parentProtected() && i==extra.parent())reviewedBeforeVerifier=false;
                    Refined helper = verification == null ? null : verification.get(i);
                    boolean corroborated = helper != null && helper.contentSupported() && c.verifier != null
                            && c.verifier.quadIou() != null && c.verifier.quadIou() >= .80;
                    if(effectiveInstances)corroborated=instanceChoices.get(i).corroborated();
                    boolean singletonSalvage = !corroborated && chosen.fitReasons().contains("poor_mask_agreement");
                    items.add(new LocalCropArbitration.Item(c.lineage.id(),
                            c.omittedFromPhotos()?null:chosen.quad(),true,chosen.contentSupported(),reviewedBeforeVerifier,
                            singletonSalvage));
                }
                if(extra.parent()>=0 && detail==null)detail=PictureModelInput.previewRgb(original,2000);
                for(var p:extra.selected()) {
                    LocalPictureLocator.requireActive();
                    float cx=0,cy=0;
                    for(Vertex v:p.quad()){cx+=v.x();cy+=v.y();}
                    var proposal=new PicturePageDecode.Proposal(p.quad(),cx/4,cy/4,"");
                    double[] transform=PictureModelInput.roiTransform(source,p.quad(),helperRoiPadding);
                    PictureModelInput.Rgb shared=detail;
                    Refined fit=fallbackAttempts.run(() -> refine(shared,source,proposal,transform,fallbackSession(),true,helperRoiPadding,"local_helper","local:"+p.parent()+":"+p.window()+":"+p.rank()));
                    fits.add(fit);
                    Vertex[] accepted=fit.contentSupported()?strictSource(fit.quad(),source):null;
                    String id="local:"+p.parent()+":"+p.window()+":"+p.rank();
                    var lineage=new Lineage(id,"local",p.parent(),p.window(),p.rank(),List.of(),
                            PicturePageDecode.canvasCorners(fit.quad(),source));
                    results.add(new Candidate(PicturePageDecode.canvasCorners(accepted,source),
                            accepted==null?"local_candidate_rejected":"local_crop_review",null,
                            accepted==null?"rejected_local":"crop",null,lineage,fit.fitEvidence()));
                    items.add(new LocalCropArbitration.Item(id,accepted,false,accepted!=null,true));
                }
                var decisions=LocalCropArbitration.resolve(items,extra.parent());
                for(int i=0;i<results.size();i++) {
                    Candidate c=results.get(i);
                    if(c.omittedFromPhotos())continue;
                    var decision=decisions.get(i);
                    if("crop".equals(decision.disposition()) || "unresolved".equals(decision.disposition()))continue;
                    List<String> children=new ArrayList<>();
                    for(int child:decision.children())children.add(results.get(child).lineage.id());
                    var l=c.lineage;
                    var lineage=new Lineage(l.id(),l.origin(),l.parent(),l.window(),l.rank(),children,c.corners);
                    results.set(i,new Candidate(null,decision.disposition(),c.fallback,decision.disposition(),c.verifier,lineage,null,null,c.instanceBoundary));
                }
                local=new LocalEvidence(extra,fits,"");
            } catch(IOException|OrtException error) {
                if(Thread.currentThread().isInterrupted() || FallbackAttempts.cancelled(error))throw error;
                local=new LocalEvidence(null,List.of(),"local_locator_unavailable");
            }
        }
        if (locatorEvidence != null) locatorEvidence.accept(page);
        // R8 runs after every ordinary global/local admission and arbitration decision.
        // It has no model access and cannot change a lineage, disposition or list order.
        BoundarySummary boundary= L1GeometryFallback.POLICY.equals(BuildConfig.OWN_L1_GEOMETRY_FALLBACK_POLICY)
                && ownLocator!=null ? applyL1Geometry(results,page.proposals(),primary,fallback,verification,source) : null;
        long crops=results.stream().filter(c->!c.omittedFromPhotos() && c.corners!=null).count();
        Map<Candidate, CropReviewImageEvidence.Alternate> alternate = Map.of();
        long reviewStart = android.os.SystemClock.elapsedRealtime();
        try {
            alternate = CropReviewImageEvidence.analyze(detail, results);
        } catch (RuntimeException | OutOfMemoryError e) {
            android.util.Log.w("PhotoScanReview", "Optional image evidence unavailable.", e);
        }
        return new Result(results, page.overflow() || crops > ScanSession.MAX_CROPS, local,
                source.getWidth(), source.getHeight(),
                android.os.SystemClock.elapsedRealtime() - reviewStart, alternate,
                BuildConfig.EXPERIMENTAL_CROP_REVIEW ? page.proposals() : List.of(),boundary);
    }

    private static BoundarySummary applyL1Geometry(List<Candidate> results,List<PicturePageDecode.Proposal> proposals,
            List<Refined> primary,List<Refined> fallback,List<Refined> verification,Size source) {
        List<L1GeometryFallback.Item> ordinary=new ArrayList<>();
        Map<String,L1GeometryFallback.Correction> corrections=new LinkedHashMap<>();
        Map<String,Boolean> guards=new LinkedHashMap<>();Map<String,Refined> selectedFits=new LinkedHashMap<>();
        Map<String,L1GeometryFallback.Helper> helperEvidence=new LinkedHashMap<>();
        for(int index=0;index<results.size();index++){
            Candidate c=results.get(index);if(c.omittedFromPhotos()||!"crop".equals(c.disposition)||c.corners==null||c.lineage==null)continue;
            String id=c.lineage.id();ordinary.add(new L1GeometryFallback.Item(id,c.corners));
            int slot="global".equals(c.lineage.origin())&&index<proposals.size()?index:-1;
            Refined selected=slot<0?null:c.fallback==null?primary.get(slot):fallback==null?null:fallback.get(slot);
            Refined checked=slot<0||verification==null?null:verification.get(slot);
            boolean matched=selected!=null&&selected.quad()!=null&&Arrays.equals(c.corners,PicturePageDecode.canvasCorners(selected.quad(),source));
            var v=c.verifier;var first=checked==null?null:checked.firstMask();
            L1GeometryFallback.Helper helper=new L1GeometryFallback.Helper(checked!=null,checked!=null&&checked.quad()==null,
                    v==null?null:v.action(),v==null?null:v.primaryReason(),checked==null?null:checked.reason(),
                    v!=null&&v.helperGeometryInvalid(),v==null?null:v.quadIou(),first!=null,
                    first!=null&&first.finite(),first==null||first.emptyMask(),first==null?0:first.activePixels());
            PicturePageDecode.Proposal p=slot<0?null:proposals.get(slot);double[][] raw=null;
            if(p!=null&&p.quad()!=null){raw=new double[4][2];for(int k=0;k<4;k++){raw[k][0]=p.quad()[k].x();raw[k][1]=p.quad()[k].y();}}
            var input=new L1GeometryFallback.Input(id,c.corners,c.reason,matched,raw,p==null?null:new double[]{p.centerX(),p.centerY()},slot>=0&&c.fallback==null,helper);
            var correction=L1GeometryFallback.propose(input,source.getWidth(),source.getHeight());
            corrections.put(id,correction);guards.put(id,!"failed_helper_guard_not_proven".equals(correction.reason()));selectedFits.put(id,selected);helperEvidence.put(id,helper);
        }
        var finalPage=L1GeometryFallback.applyPage(ordinary,corrections,source.getWidth(),source.getHeight());
        for(int index=0;index<results.size();index++){
            Candidate c=results.get(index);if(c.lineage==null||!corrections.containsKey(c.lineage.id()))continue;
            String id=c.lineage.id();var correction=corrections.get(id);float[] corners=finalPage.corners().get(id);boolean changed=corners!=c.corners;
            CropReviewPolicy.FitEvidence fit=c.fitEvidence;VerifierEvidence verifier=c.verifier;String scope="ordinary_pre_boundary_fit_unchanged";
            if(changed){
                Refined selected=selectedFits.get(id);ReviewReference reference=selected==null?null:selected.reviewReference();fit=null;
                if(reference!=null)try{fit=PictureQuadFit.reviewAtSourceQuad(reference.mask(),reference.edge(),ROI/4,ROI/4,reference.transform(),L1GeometryFallback.source(corners,source.getWidth(),source.getHeight()));}
                catch(RuntimeException|OutOfMemoryError failure){android.util.Log.w("PhotoScanBoundary","Optional retained-ROI support unavailable",failure);}
                scope=fit==null?"new_outline_support_unknown_original_fit_in_pre_boundary_diagnostics":"new_stored_outline_against_retained_original_roi_mask_outside_roi_unknown";
                if(verifier!=null){
                    // R8 requires a genuinely null checked helper; its null agreement remains honest.
                    verifier=new VerifierEvidence(verifier.action(),verifier.primaryReason(),verifier.helperReason(),verifier.firstMask(),corners,
                            false,null,verifier.helperGeometryInvalid());
                }
            }
            String decision=finalPage.reverted()&&correction.accepted()?"whole_page_peer_rollback":correction.reason();
            var h=helperEvidence.get(id);
            var evidence=new BoundaryEvidence(L1GeometryFallback.POLICY,c.reason,decision,guards.get(id),h.ran(),h.quadNull(),changed,finalPage.reverted(),
                    correction.rawCoverage(),correction.areaRatio(),correction.minimumDistance(),correction.originalRetention(),
                    c.corners,corners,c.fitEvidence,c.verifier,scope);
            results.set(index,new Candidate(corners,c.reason,c.fallback,c.disposition,verifier,c.lineage,fit,evidence,c.instanceBoundary));
        }
        return new BoundarySummary(L1GeometryFallback.POLICY,ordinary.size(),finalPage.corners().size(),finalPage.reverted(),finalPage.conflicts());
    }

    private static Vertex[] strictSource(Vertex[] quad,Size source) {
        if(quad==null)return null;
        for(Vertex v:quad)if(v.x()<0 || v.y()<0 || v.x()>source.getWidth()-1 || v.y()>source.getHeight()-1)return null;
        return quad;
    }

    record SelectedBranch(Refined refined,boolean acceptedFallback,boolean reviewedBeforeVerifier) {}

    static SelectedBranch selectedForLocal(Refined primary,Refined actualFallback,Vertex[] finalQuad,
                                          boolean primaryOverlap) {
        if(actualFallback==null) {
            return new SelectedBranch(primary,false,primaryOverlap || !primary.reason().isEmpty());
        }
        boolean accepted=finalQuad!=null;
        return new SelectedBranch(accepted?actualFallback:new Refined(null,"own_fallback_rejected"),accepted,true);
    }

    private PicturePageDecode.Page coarse(File original, Size source) throws IOException, OrtException {
        double[] transform = PictureModelInput.pageTransform(source);
        var preview = PictureModelInput.previewRgb(original, 1000);
        PictureModelInput.pack(PictureModelInput.warp(preview, source, transform, PAGE), pageInput, PAGE, 0, 0);
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, pageInput, new long[]{1, 3, PAGE, PAGE});
             OrtSession.Result outputs = pageSession.run(Collections.singletonMap("image", tensor))) {
            return PicturePageDecode.decode(read(outputs, MASK, true), read(outputs, EDGE, true),
                    read(outputs, CENTER, true), read(outputs, VECTORS, false), transform, source, PAGE / 4);
        }
    }

    record Refined(Vertex[] quad, String reason, int borderContacts, List<String> fitReasons,
                   FirstMaskEvidence firstMask, boolean contentSupported, CropReviewPolicy.FitEvidence fitEvidence,ReviewReference reviewReference) {
        Refined(Vertex[] quad,String reason,int borderContacts,List<String> fitReasons,FirstMaskEvidence firstMask,
                boolean contentSupported,CropReviewPolicy.FitEvidence fitEvidence) {
            this(quad,reason,borderContacts,fitReasons,firstMask,contentSupported,fitEvidence,null);
        }
        Refined(Vertex[] quad, String reason, int borderContacts, List<String> fitReasons,
                FirstMaskEvidence firstMask, boolean contentSupported) {
            this(quad,reason,borderContacts,fitReasons,firstMask,contentSupported,null);
        }
        Refined(Vertex[] quad, String reason) { this(quad,reason,0); }
        Refined(Vertex[] quad, String reason, int borderContacts) {
            this(quad,reason,borderContacts,reason.isEmpty()?List.of():List.of(reason),null,false);
        }
    }

    static boolean semanticBackground(Refined helper) {
        if (helper == null || helper.quad() != null || helper.firstMask() == null) return false;
        FirstMaskEvidence first = helper.firstMask();
        return first.emptyMask() && first.activePixels() == 0 && first.finite()
                && Double.isFinite(first.maximum()) && first.maximum() >= 0 && first.maximum() <= .05;
    }

    static Double agreement(Vertex[] primary,Vertex[] helper,int width,int height) {
        if(!PictureQuadFit.validSourceQuad(primary,width,height)
                || !PictureQuadFit.validSourceQuad(helper,width,height))return null;
        double iou=PicturePageDecode.iou(primary,helper);
        return Double.isFinite(iou)?iou:null;
    }

    /** A checked secondary failure disables further secondary calls for this page. */
    static final class FallbackAttempts {
        interface Work {
            Refined run() throws IOException, OrtException;
        }

        private boolean unavailable;

        Refined runIfNeeded(Refined primary, Vertex[] proposal, Work work) throws IOException, OrtException {
            if (!eligibleFallbackProposal(proposal) || meetsPrimaryArea(primary.quad(), proposal)) return null;
            return run(work);
        }

        Refined run(Work work) throws IOException, OrtException {
            requireUninterrupted();
            if (!unavailable) {
                try {
                    Refined result = work.run();
                    requireUninterrupted();
                    return result;
                } catch (IOException | OrtException error) {
                    if (Thread.currentThread().isInterrupted() || cancelled(error)) throw error;
                    unavailable = true;
                }
            }
            return new Refined(null, "own_fallback_unavailable");
        }

        private static void requireUninterrupted() throws InterruptedIOException {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Picture processing interrupted.");
        }

        private static boolean cancelled(Throwable error) {
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof InterruptedException || cause instanceof InterruptedIOException
                        || cause instanceof CancellationException) return true;
            }
            return false;
        }
    }

    private OrtSession fallbackSession() throws IOException, OrtException {
        if (fallbackSession == null) {
            fallbackSession = open(fallbackContext.getAssets().open(OWN_FALLBACK_ROI_ASSET));
        }
        return fallbackSession;
    }

    static boolean meetsPrimaryArea(Vertex[] refined, Vertex[] proposal) {
        return meetsAreaRatio(refined, proposal, PRIMARY_MIN_AREA_RATIO);
    }

    private static boolean meetsAreaRatio(Vertex[] refined, Vertex[] proposal, double minimum) {
        if (refined == null || refined.length != 4 || proposal == null || proposal.length != 4) return false;
        double proposalArea = area(proposal);
        double refinedArea = area(refined);
        return Double.isFinite(proposalArea) && proposalArea > 0 && Double.isFinite(refinedArea)
                && refinedArea >= minimum * proposalArea;
    }

    static Vertex[] acceptedFallback(Vertex[] refined, Vertex[] proposal, int width, int height) {
        return acceptedFallback(refined, proposal, width, height, MIXED_FALLBACK);
    }

    static Vertex[] acceptedFallback(Vertex[] refined, Vertex[] proposal, int width, int height, String policy) {
        double minimum;
        if (MIXED_FALLBACK.equals(policy)) minimum = .25;
        else if (MIXED_FALLBACK_V2.equals(policy)) minimum = .10;
        else throw new IllegalArgumentException("Unsupported fallback area policy.");
        if (!meetsAreaRatio(refined, proposal, minimum)
                || !PictureQuadFit.validSourceQuad(refined, width, height)) return null;
        for (Vertex vertex : refined) {
            if (vertex.x() < 0 || vertex.y() < 0 || vertex.x() > width - 1 || vertex.y() > height - 1) return null;
        }
        return refined;
    }

    static boolean eligibleFallbackProposal(Vertex[] proposal) {
        if (proposal == null || proposal.length != 4) return false;
        int sign = 0;
        for (int i = 0; i < 4; i++) {
            Vertex a = proposal[i], b = proposal[(i + 1) % 4], c = proposal[(i + 2) % 4];
            double turn = (b.x() - (double) a.x()) * (c.y() - (double) a.y())
                    - (b.y() - (double) a.y()) * (c.x() - (double) a.x());
            if (!Double.isFinite(turn) || turn == 0) return false;
            int next = turn > 0 ? 1 : -1;
            if (sign != 0 && sign != next) return false;
            sign = next;
        }
        return true;
    }

    private static double area(Vertex[] quad) {
        double sum = 0;
        for (int i = 0; i < quad.length; i++) {
            Vertex a = quad[i], b = quad[(i + 1) % quad.length];
            sum += (double) a.x() * b.y() - (double) b.x() * a.y();
        }
        return Math.abs(sum) / 2;
    }

    private Refined refine(PictureModelInput.Rgb detail, Size source, PicturePageDecode.Proposal proposal,
                           double[] transform, OrtSession model, boolean useNativeFirstHull, double requestedPadding,
                           String stage, String lineage) throws OrtException {
        if (transform == null) return new Refined(null, "invalid_roi_geometry");
        Refined first = fitRoi(detail, source, proposal, transform, recoverConstrained, false, model, useNativeFirstHull, requestedPadding, stage, lineage);
        if (!retrySourceEdges || first.quad() != null || first.borderContacts() == 0) return first;
        double[] retryTransform = PictureModelInput.sourceEdgeRetryTransform(
                source, proposal.quad(), transform, first.borderContacts(), requestedPadding);
        if (retryTransform == null) return first;
        Refined retry = fitRoi(detail, source, proposal, retryTransform, false, true, model, useNativeFirstHull, requestedPadding, stage, lineage);
        if (retry.quad() == null) return first;
        for (Vertex point : retry.quad()) {
            if (point.x() < 0 || point.y() < 0 || point.x() > source.getWidth() - 1
                    || point.y() > source.getHeight() - 1) return first;
        }
        return new Refined(retry.quad(),"source_edge_context_retry",0,retry.fitReasons(),first.firstMask(),true,
                retry.fitEvidence());
    }

    private Refined fitRoi(PictureModelInput.Rgb detail, Size source, PicturePageDecode.Proposal proposal,
                           double[] transform, boolean constrain, boolean requireContentSupport,
                           OrtSession model, boolean useNativeFirstHull, double requestedPadding,
                           String stage, String lineage) throws OrtException {
        float cx = (float) (proposal.centerX() * transform[0] + transform[2]);
        float cy = (float) (proposal.centerY() * transform[4] + transform[5]);
        if (cx < 0 || cy < 0 || cx > ROI - 1 || cy > ROI - 1) return new Refined(null, "target_center_outside_roi");
        PictureModelInput.pack(PictureModelInput.warp(detail, source, transform, ROI), roiInput, ROI, cx, cy);
        boolean primaryModel = model == roiSession;
        refinerCalls.add(new RefinerCall(stage,lineage,primaryModel?"primary":"helper",
                primaryModel?BuildConfig.OWN_PICTURE_ROI_SHA256:BuildConfig.OWN_PICTURE_FALLBACK_SHA256,
                requestedPadding,requireContentSupport,transform.clone(),cx,cy,refinerInputSha256()));
        var observation=RoiObservation.begin(refinerCalls.get(refinerCalls.size()-1),proposal,roiInput,constrain,useNativeFirstHull);
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, roiInput, new long[]{1, 4, ROI, ROI});
             OrtSession.Result outputs = model.run(Collections.singletonMap("image_rgb_prior", tensor))) {
            float[] mask = read(outputs, MASK, true), edge = read(outputs, EDGE, true);
            RoiObservation.tensorInfo(observation,tensor,outputs);
            float[] observedMaskLogits=observation==null?null:RoiObservation.raw(outputs,MASK);
            float[] observedEdgeLogits=observation==null?null:RoiObservation.raw(outputs,EDGE);
            var recovery = constrain ? PictureQuadFit.fitWithConstrainedRecovery(mask, edge, ROI / 4, ROI / 4,
                    transform, source.getWidth(), source.getHeight(), useNativeFirstHull) : null;
            var fit = recovery != null ? recovery.result() : requireContentSupport
                    ? PictureQuadFit.fitSourceEdgeRetry(mask, edge, ROI / 4, ROI / 4)
                    : PictureQuadFit.fit(mask, edge, ROI / 4, ROI / 4);
            int active = 0;
            double maximum = Double.NEGATIVE_INFINITY;
            boolean finite = true;
            for (float probability : mask) {
                if (probability >= .5f) active++;
                maximum = Math.max(maximum,probability);
                finite &= Float.isFinite(probability);
            }
            var firstMask = new FirstMaskEvidence(fit.reasons().contains("empty_mask"),active,maximum,finite);
            if (requireContentSupport && !PictureQuadFit.contentSupported(fit)) {
                return RoiObservation.finish(observation,observedMaskLogits,observedEdgeLogits,mask,edge,fit,recovery,
                        captureInstance(refinerCalls.get(refinerCalls.size()-1),proposal,mask,edge,
                                new Refined(null,PicturePageDecode.fitReason(fit,null),0,fit.reasons(),firstMask,false)));
            }
            Vertex[] quad = PicturePageDecode.sourceQuad(fit, transform, source);
            String reason = proposal.reason().isEmpty() ? PicturePageDecode.fitReason(fit, quad) : proposal.reason();
            boolean supported=PictureQuadFit.contentSupported(fit) || recovery!=null
                    && recovery.constrainedSupport()!=null && PictureQuadFit.contentSupported(recovery.constrainedSupport());
            Refined returned=new Refined(quad,reason,recovery == null ? 0 : recovery.borderContacts(),
                    fit.reasons(),firstMask,supported,quad == null ? null : reviewEvidence(quad,fit),
                    quad!=null&&model==roiSession&&"weak_side".equals(reason)
                            &&L1GeometryFallback.POLICY.equals(BuildConfig.OWN_L1_GEOMETRY_FALLBACK_POLICY)
                            ?new ReviewReference(mask,edge,transform.clone()):null);
            return RoiObservation.finish(observation,observedMaskLogits,observedEdgeLogits,mask,edge,fit,recovery,
                    captureInstance(refinerCalls.get(refinerCalls.size()-1),proposal,mask,edge,returned));
        }
    }

    private static CropReviewPolicy.FitEvidence reviewEvidence(Vertex[] quad, PictureQuadFit.Result fit) {
        boolean weakEdge = fit.reasons().contains("weak_side");
        for (double continuity : fit.sideContinuity()) weakEdge |= continuity < .65;
        for (double support : fit.sideSupport()) weakEdge |= support < .25;
        double horizontal = Math.max(sideLength(quad[0], quad[1]), sideLength(quad[2], quad[3]));
        double vertical = Math.max(sideLength(quad[1], quad[2]), sideLength(quad[3], quad[0]));
        double aspect = Math.max(horizontal, vertical) / Math.min(horizontal, vertical);
        return new CropReviewPolicy.FitEvidence(fit.omitted(), fit.expansion(), weakEdge, aspect);
    }

    private static double sideLength(Vertex first, Vertex second) {
        return Math.hypot(second.x() - first.x(), second.y() - first.y());
    }

    static float[] read(OrtSession.Result outputs, String name, boolean probability) {
        FloatBuffer buffer = ((OnnxTensor) outputs.get(name).orElseThrow(() -> new IllegalStateException("Missing model output " + name))).getFloatBuffer();
        float[] values = new float[buffer.remaining()];
        buffer.get(values);
        if (probability) {
            for (int i = 0; i < values.length; i++) values[i] = PicturePageDecode.probability(values[i]);
        }
        return values;
    }

    @Override public synchronized void close() throws OrtException {
        try {
            if (fallbackSession != null) fallbackSession.close();
        } finally {
            try {
                roiSession.close();
            } finally {
                closeLocator();
            }
        }
    }

    private void closeLocator() throws OrtException {
        if (ownLocator != null) ownLocator.close();
        else pageSession.close();
    }
}




