package local.photoscan;

import org.json.JSONException;
import org.json.JSONObject;

final class ScanMetadata {
    private ScanMetadata() {}

    static int expectedCount(int count) { return count >= 1 && count <= 6 ? count : 0; }

    static JSONObject source(String origin) throws JSONException {
        return new JSONObject().put("origin", origin);
    }

    static JSONObject camera(ScanController.CaptureOptions options, long order) throws JSONException {
        return source("camera").put("capture_order", order).put("capture_reserved_at_ms", System.currentTimeMillis())
                .put("capture_build", build())
                .put("expected_count", expectedCount(options.expectedCount()))
                .put("exposure", new JSONObject()
                        .put("requested_ev", finite(options.requestedExposureEv()))
                        .put("requested_index", known(options.requestedExposureIndex()))
                        .put("applied_index", known(options.appliedExposureIndex()))
                        .put("applied_ev", finite(options.appliedExposureEv()))
                        .put("step_ev", finite(options.exposureStepEv()))
                        .put("applied_status", options.appliedExposureIndex() == null ? "unknown" : "confirmed"));
    }

    static JSONObject collectionCapture(ScanSession album, String collectionId, ScanController.CaptureVariant variant)
            throws java.io.IOException, JSONException {
        if (album.physicalBook(album.physicalBookId) == null) {
            throw new java.io.IOException("Start a physical album before taking photos.");
        }
        String scene = variant == null ? ScanSession.newId() : variant.sceneId();
        int ordinal = variant == null ? 1 : variant.ordinal();
        int planned = variant == null ? 1 : variant.plannedCount();
        String role = variant == null ? "normal" : variant.role();
        String policy = variant == null ? (album.collectionSingleOnly ? "single_only" : "sampled_normal")
                : variant.policy();
        boolean unavailableAlternates = variant != null && variant.unavailableAlternates();
        int bookSceneOrdinal = album.nextCollectionSceneOrdinal();
        try {
            if (scene == null || !java.util.UUID.fromString(scene).toString().equals(scene)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            throw new java.io.IOException("Invalid capture set identifier.", invalid);
        }
        if (ordinal < 1 || ordinal > planned || (planned != 1 && planned != 3)
                || !plannedRole(planned, ordinal).equals(role)
                || !(planned == 3 ? "sampled_bracket".equals(policy) || "forced_bracket".equals(policy)
                        : "sampled_normal".equals(policy) || "single_only".equals(policy))
                || (unavailableAlternates && (planned != 3 || ordinal != 1))) {
            throw new java.io.IOException("Invalid capture set member.");
        }
        if (variant != null) for (ScanSession.Source source : album.sources) {
            JSONObject existing = source.collectionCapture();
            if (existing == null || !scene.equals(existing.optString("scene_id"))) continue;
            if (!album.physicalBookId.equals(source.physicalBookId())
                    || planned != existing.optInt("planned_count") || ordinal == existing.optInt("ordinal")
                    || !policy.equals(existing.optString("sampling_policy"))) {
                throw new java.io.IOException("That capture set member does not match the saved set.");
            }
            bookSceneOrdinal = existing.optInt("book_scene_ordinal");
        }
        if (variant != null) for (JSONObject attempt : album.collectionUnstored) {
            JSONObject existing = attempt.optJSONObject("collection");
            if (existing == null || !scene.equals(existing.optString("scene_id"))) continue;
            if (!album.physicalBookId.equals(existing.optString("physical_book_id"))
                    || planned != existing.optInt("planned_count")
                    || !policy.equals(existing.optString("sampling_policy"))) {
                throw new java.io.IOException("That capture set member does not match the saved set.");
            }
            bookSceneOrdinal = existing.optInt("book_scene_ordinal");
        }
        return new JSONObject().put("collection_id", collectionId)
                .put("physical_book_id", album.physicalBookId)
                .put("scene_id", scene).put("ordinal", ordinal).put("planned_count", planned).put("role", role)
                .put("book_scene_ordinal", bookSceneOrdinal).put("sampling_policy", policy)
                .put("unavailable_alternates", unavailableAlternates)
                .put("ae_state", JSONObject.NULL).put("ae_state_status", "not_recorded")
                .put("exposure_confirmation", "camera_control_when_confirmed");
    }

    static String plannedRole(int planned, int ordinal) {
        return planned == 3 && ordinal == 2 ? "darker" : planned == 3 && ordinal == 3 ? "brighter"
                : "normal";
    }

    static JSONObject derived(String origin, ScanSession.Source parent) throws JSONException {
        var metadata = parent.captureMetadata;
        return source(origin).put("parent_source_id", parent.id)
                .put("expected_count", metadata == null || !"redetect".equals(origin) ? JSONObject.NULL
                        : known(metadata.opt("expected_count")))
                .put("original_capture", known(metadata == null ? null :
                        "redetect".equals(metadata.optString("origin"))
                                ? metadata.optJSONObject("original_capture") : metadata));
    }

    static JSONObject detection(PageProcessingQueue.Request request, PageProcessingQueue.Detection result,
                                long order) throws JSONException {
        JSONObject metadata=new JSONObject().put("outcome", result == null ? "failure" : "success")
                .put("detected_count", result == null ? JSONObject.NULL : result.corners.size())
                .put("unoutlined_count", result == null ? JSONObject.NULL : result.unoutlined)
                .put("warnings", result == null ? JSONObject.NULL : new org.json.JSONArray(result.warnings))
                .put("completion_order", order).put("completed_at_ms", System.currentTimeMillis())
                .put("queue_ms", duration(request.queuedElapsed, request.startedElapsed))
                .put("detect_ms", duration(request.startedElapsed, request.finishedElapsed))
                .put("build", build()).put("settings", detectorSettings());
        metadata.put("requested_instance_boundary_policy",known(request.requestedInstanceBoundaryPolicy));
        if(result!=null&&result.boundaryDiagnostics!=null)metadata.put("boundary_geometry",result.boundaryDiagnostics);
        if(result!=null&&result.instanceBoundaryDiagnostics!=null)metadata.put("instance_boundary",result.instanceBoundaryDiagnostics);
        return metadata;
    }

    static JSONObject detectorSettings() throws JSONException {
        return new JSONObject().put("own_picture_models", BuildConfig.USE_OWN_PICTURE_MODELS)
                .put("locator_supplement", BuildConfig.OWN_PICTURE_SUPPLEMENT)
                .put("roi_padding", BuildConfig.OWN_PICTURE_ROI_PADDING)
                .put("primary_roi_padding", BuildConfig.OWN_PICTURE_ROI_PADDING)
                .put("helper_roi_padding", BuildConfig.OWN_PICTURE_HELPER_ROI_PADDING)
                .put("roi_context_policy", BuildConfig.OWN_PICTURE_CONTEXT_POLICY)
                .put("roi_recovery", BuildConfig.OWN_PICTURE_ROI_RECOVERY)
                .put("roi_edge_retry", BuildConfig.OWN_PICTURE_ROI_EDGE_RETRY)
                .put("first_hull", BuildConfig.OWN_PICTURE_FIRST_HULL)
                .put("fallback_policy", BuildConfig.OWN_PICTURE_FALLBACK_POLICY)
                .put("verifier_policy", BuildConfig.OWN_PICTURE_VERIFIER_POLICY)
                .put("local_policy", BuildConfig.OWN_PICTURE_LOCAL_POLICY)
                .put("l1_geometry_fallback_policy",BuildConfig.OWN_L1_GEOMETRY_FALLBACK_POLICY)
                .put("local_arbitration_policy", LocalCropArbitration.POLICY)
                .put("locator_sha256", hash(BuildConfig.PRINT_LOCATOR_SHA256))
                .put("roi_sha256", hash(BuildConfig.OWN_PICTURE_ROI_SHA256))
                .put("fallback_sha256", hash(BuildConfig.OWN_PICTURE_FALLBACK_SHA256));
    }

    /** Stored with the detection; exports never consult the current developer preference. */
    static JSONObject instanceBoundaryDiagnostics(PictureContentDetector detector,PictureContentDetector.Result result,
                                                  boolean requested) throws JSONException {
        return instanceBoundaryDiagnostics(result,requested,detector.instanceChoices(),detector.instanceChooserUnavailable())
                .put("compute_timing",instanceTiming(detector.instanceTiming()));
    }
    static JSONObject instanceBoundaryDiagnostics(PictureContentDetector.Result result,boolean requested,
                    java.util.List<InstanceBoundaryIntegration.Effective> actualChoices,String unavailable) throws JSONException {
        int width=result.reviewSourceWidth,height=result.reviewSourceHeight;
        var choices=new org.json.JSONArray();int slot=0;
        for(var effective:actualChoices){
            var choice=effective.choice();var p=effective.provenance();var branch=effective.branch();
            choices.put(new JSONObject().put("lineage","global:"+(slot++)).put("decision",choice.decision)
                    .put("changed",choice.changed).put("selected_id",choice.selected.id).put("selected_kind",p.kind())
                    .put("selected_role",p.role()).put("selected_branch",p.branch()).put("selected_call_id",known(p.callId()))
                    .put("ordinary_quad",sourceQuad(effective.originalCorners(),width,height))
                    .put("chosen_prelocal_quad",sourceQuad(effective.chosenCorners(),width,height))
                    .put("effective_content_supported",branch.refined().contentSupported())
                    .put("effective_fit_reasons",new org.json.JSONArray(branch.refined().fitReasons()))
                    .put("effective_accepted_fallback",branch.acceptedFallback())
                    .put("effective_reviewed_before_verifier",branch.reviewedBeforeVerifier())
                    .put("independent_other_role_corroborated",effective.corroborated())
                    .put("fit_evidence_scope",effective.scope()).put("original_actual_fit",fitEvidence(p.returned().fitEvidence()))
                    .put("original_actual_reason",p.returned().reason())
                    .put("original_measurement",instanceMeasurement(choice.originalFeatures))
                    .put("selected_measurement",instanceMeasurement(choice.features)));
        }
        var outputs=new org.json.JSONArray();
        for(var candidate:result.candidates)outputs.put(new JSONObject()
                .put("lineage",candidate.lineage==null?JSONObject.NULL:candidate.lineage.id())
                .put("disposition",candidate.disposition).put("reason",candidate.reason)
                .put("final_automatic_quad",sourceQuad(candidate.corners,width,height))
                .put("c1_changed_before_local",candidate.instanceBoundary!=null&&candidate.instanceBoundary.choice().changed));
        return new JSONObject().put("requested_policy",requested?InstanceBoundaryIntegration.POLICY:"none")
                .put("effective_policy",requested&&unavailable==null?InstanceBoundaryIntegration.POLICY:"none")
                .put("unavailable",known(unavailable)).put("ordinary_page_retained_on_unavailable",requested&&unavailable!=null)
                .put("host_endpoint_sha256","090abe08bdd3e4374a8ee5fcd5cef3e760fd426e10e6ae1b1e54414da72e54da")
                .put("capability",new JSONObject().put("available",InstanceBoundarySettings.supported())
                        .put("minimum_api",33).put("device_api",android.os.Build.VERSION.SDK_INT)
                        .put("unavailable_reason",known(InstanceBoundarySettings.unavailableReason())))
                .put("geometry_scope","automatic_detection_before_manual_edits")
                .put("coordinates","upright_source_pixel_centers").put("source_width",width).put("source_height",height)
                .put("choices",choices).put("final_outputs",outputs);
    }
    static Object instanceTiming(InstanceBoundaryIntegration.Timing t) throws JSONException {
        if(t==null)return JSONObject.NULL;
        return new JSONObject().put("capture_ms",t.captureNanos()/1e6).put("evidence_ms",t.evidenceNanos()/1e6)
                .put("generation_ms",t.generationNanos()/1e6).put("feature_setup_ms",t.featureNanos()/1e6)
                .put("scoring_ms",t.scoringNanos()/1e6).put("apply_ms",t.applyNanos()/1e6)
                .put("total_ms",(t.captureNanos()+t.applyNanos())/1e6).put("evidence_is_subset_of_capture",true)
                .put("captured_views",t.capturedViews()).put("generated_pools",t.generatedPools())
                .put("generated_candidates",t.generatedCandidates()).put("scope","C1 compute only; excludes model calls and observation ZIP writes");
    }

    private static Object instanceMeasurement(InstanceBoundaryMath.Measurement m) throws JSONException {
        if(m==null)return JSONObject.NULL;
        return new JSONObject().put("risk",finite(m.risk)).put("boundary",finite(m.boundary))
                .put("mask_fn",finite(m.maskFn)).put("mask_fp",finite(m.maskFp)).put("peer_overlap",finite(m.peerOverlap))
                .put("l1_disagreement",finite(m.l1Disagreement)).put("side_support",new org.json.JSONArray(m.sideSupport));
    }

    static JSONObject boundaryDiagnostics(PictureContentDetector.Result result) throws JSONException {
        var summary=result.boundarySummary;if(summary==null)return null;
        var candidates=new org.json.JSONArray();int width=result.reviewSourceWidth,height=result.reviewSourceHeight;
        for(var candidate:result.candidates){
            var e=candidate.boundaryEvidence;if(e==null)continue;
            candidates.put(new JSONObject().put("lineage",candidate.lineage==null?JSONObject.NULL:candidate.lineage.id())
                    .put("policy",e.policy()).put("reported_retained_reason",e.reportedReason())
                    .put("decision",e.decision()).put("guard_passed",e.guardPassed())
                    .put("helper_executed",e.helperExecuted()).put("helper_quad_null",e.helperQuadNull()).put("accepted",e.accepted())
                    .put("page_rollback",e.pageRollback()).put("raw_coverage",finite(e.rawCoverage()))
                    .put("area_ratio",finite(e.areaRatio())).put("storage_min_original_side_distance_px",finite(e.minimumDistance()))
                    .put("stored_original_area_retention",finite(e.originalRetention()))
                    .put("ordinary_quad",sourceQuad(e.originalCorners(),width,height)).put("final_quad",sourceQuad(e.finalCorners(),width,height))
                    .put("pre_boundary_fit",fitEvidence(e.originalFit())).put("current_fit",fitEvidence(candidate.fitEvidence))
                    .put("fit_evidence_scope",e.fitScope()).put("pre_boundary_verifier",verifierEvidence(e.originalVerifier(),width,height))
                    .put("current_verifier",verifierEvidence(candidate.verifier,width,height)));
        }
        var conflicts=new org.json.JSONArray();for(var c:summary.conflicts())conflicts.put(new JSONObject()
                .put("first",c.first()).put("second",c.second()).put("old_ratio",c.oldRatio()).put("new_ratio",c.newRatio()));
        return new JSONObject().put("policy",summary.policy()).put("after_ordinary_arbitration",true).put("additional_model_calls",0)
                .put("ordinary_count",summary.ordinaryCount()).put("final_count",summary.finalCount())
                .put("page_rollback",summary.pageRollback()).put("conflicts",conflicts).put("candidates",candidates);
    }
    private static Object sourceQuad(float[] corners,int width,int height) throws JSONException {
        if(corners==null)return JSONObject.NULL;var q=new org.json.JSONArray();
        for(int i=0;i<4;i++)q.put(new org.json.JSONArray().put((double)corners[2*i]*width-.5).put((double)corners[2*i+1]*height-.5));return q;
    }
    private static Object fitEvidence(CropReviewPolicy.FitEvidence fit) throws JSONException {
        if(fit==null)return JSONObject.NULL;
        return new JSONObject().put("omitted",finite(fit.omitted())).put("expansion",finite(fit.expansion()))
                .put("weak_edge",fit.weakEdge()).put("aspect",finite(fit.aspect()));
    }
    private static Object verifierEvidence(PictureContentDetector.VerifierEvidence v,int width,int height) throws JSONException {
        if(v==null)return JSONObject.NULL;var first=v.firstMask();Object mask=first==null?JSONObject.NULL:new JSONObject()
                .put("empty_mask",first.emptyMask()).put("active_pixels",first.activePixels()).put("finite",first.finite()).put("max_mask_probability",finite(first.maximum()));
        return new JSONObject().put("action",v.action()).put("reported_primary_reason",v.primaryReason()).put("helper_reason",v.helperReason())
                .put("first_pass",mask).put("primary_quad",sourceQuad(v.primaryCorners(),width,height))
                .put("geometry_disagreement",v.geometryDisagreement()).put("quad_iou",finite(v.quadIou()))
                .put("helper_geometry_invalid",v.helperGeometryInvalid());
    }

    private static JSONObject build() throws JSONException {
        return new JSONObject().put("version_name", BuildConfig.VERSION_NAME)
                .put("version_code", BuildConfig.VERSION_CODE);
    }

    private static Object hash(String value) { return value.isEmpty() ? JSONObject.NULL : value; }

    static Object known(Object value) { return value == null ? JSONObject.NULL : value; }
    private static Object finite(Double value) {
        return value != null && Double.isFinite(value) ? value : JSONObject.NULL;
    }
    private static Object duration(long start, long end) {
        return start >= 0 && end >= start ? end - start : JSONObject.NULL;
    }
}
