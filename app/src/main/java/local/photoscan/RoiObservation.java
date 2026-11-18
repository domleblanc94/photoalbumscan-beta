package local.photoscan;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.List;
import java.util.zip.*;
import org.json.*;

/** Evaluation-only observer. Copies existing values; never runs a model, warp or fitter. */
final class RoiObservation implements AutoCloseable {
    private static final ThreadLocal<RoiObservation> ACTIVE = new ThreadLocal<>();
    private final ZipOutputStream zip;
    private final JSONArray pages = new JSONArray();
    private JSONObject page, currentCall;
    private JSONArray calls;
    private String sourceId;

    RoiObservation(File file) throws IOException {
        if (ACTIVE.get()!=null) throw new IllegalStateException("Nested observation");
        zip=new ZipOutputStream(new FileOutputStream(file));zip.setLevel(1);ACTIVE.set(this);
    }
    void page(String id,String sha,int width,int height) throws JSONException {
        sourceId=id;currentCall=null;calls=new JSONArray();
        page=new JSONObject().put("source_id",id).put("source_sha256",sha)
                .put("width",width).put("height",height).put("calls",calls);pages.put(page);
    }
    static JSONObject begin(PictureContentDetector.RefinerCall info, PicturePageDecode.Proposal proposal,
                            FloatBuffer input,boolean constrain,boolean nativeHull) {
        var a=ACTIVE.get();if(a==null)return null;
        try {
            if(a.currentCall!=null)throw new AssertionError("Unfinished observation");
            String id=a.sourceId+"/call-"+a.calls.length();
            var call=new JSONObject().put("id",id).put("stage",info.stage()).put("lineage",info.lineage())
                    .put("model_role",info.role()).put("model_sha256",info.modelSha256())
                    .put("retry",info.retry()).put("requested_padding",info.requestedPadding())
                    .put("constrained_recovery",constrain).put("native_hull",nativeHull)
                    .put("proposal_source",quad(proposal.quad())).put("proposal_reason",proposal.reason())
                    .put("source_center",new JSONArray().put(proposal.centerX()).put(proposal.centerY()))
                    .put("roi_center",new JSONArray().put(info.cueX()).put(info.cueY()))
                    .put("source_to_input",numbers(info.sourceToInput()))
                    .put("input",a.payload(id+"-input.f32",floats(input)))
                    .put("actual_components",new JSONArray());
            a.calls.put(call);a.currentCall=call;return call;
        }catch(IOException|JSONException error){throw new AssertionError("Observation input failed",error);}
    }
    /** Reads output storage through a separate read-only view; original buffer position stays untouched. */
    static float[] raw(ai.onnxruntime.OrtSession.Result result,String name) {
        var tensor=(ai.onnxruntime.OnnxTensor)result.get(name).orElseThrow();
        var view=tensor.getFloatBuffer().asReadOnlyBuffer();view.rewind();
        float[] copy=new float[view.remaining()];view.get(copy);return copy;
    }
    static void tensorInfo(JSONObject call,ai.onnxruntime.OnnxTensor input,ai.onnxruntime.OrtSession.Result result) {
        if(call==null)return;
        try {
            var outputs=new JSONObject();
            for(String name:new String[]{"mask_logits","boundary_logits"}) {
                var t=(ai.onnxruntime.OnnxTensor)result.get(name).orElseThrow();var info=t.getInfo();
                outputs.put(name,new JSONObject().put("shape",new JSONArray(info.getShape()))
                        .put("element_type",info.type.toString()));
            }
            var info=input.getInfo();
            call.put("actual_input_tensor",new JSONObject().put("shape",new JSONArray(info.getShape()))
                    .put("element_type",info.type.toString())).put("actual_output_tensors",outputs);
        }catch(JSONException error){throw new AssertionError("Observation tensor metadata failed",error);}
    }
    /** Called only at the existing fitter's actual OpenCV selection site. No recomputation. */
    static void component(int width,int height,int count,int selected,int area,int active,
                          int[] membership,boolean constrained,boolean nativeHull) {
        var a=ACTIVE.get();if(a==null||a.currentCall==null)return;
        try {
            var out=a.currentCall.getJSONArray("actual_components");
            String id=a.currentCall.getString("id")+"-component-"+out.length();
            var bytes=ByteBuffer.allocate(membership.length*Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            for(int value:membership)bytes.putInt(value);
            var center=a.currentCall.getJSONArray("roi_center");
            double gx=(center.getDouble(0)-1.5)/4,gy=(center.getDouble(1)-1.5)/4;
            int x=(int)Math.floor(gx+.5),y=(int)Math.floor(gy+.5);
            boolean inside=x>=0&&y>=0&&x<width&&y<height;
            out.put(new JSONObject().put("width",width).put("height",height).put("component_count_including_background",count)
                    .put("selected_id",selected).put("selected_area",area).put("active_pixels",active)
                    .put("constrained_recovery",constrained).put("native_hull",nativeHull)
                    .put("membership_i32",a.payload(id+"-labels.i32",bytes.array()))
                    .put("cue_grid",new JSONArray().put(gx).put(gy))
                    .put("nearest_cell",new JSONArray().put(x).put(y))
                    .put("nearest_cell_component_id",inside?membership[y*width+x]:JSONObject.NULL)
                    .put("cue_membership_semantics","Diagnostic nearest grid cell only; no GT/ownership assumption"));
        }catch(IOException|JSONException error){throw new AssertionError("Observation component failed",error);}
    }
    static PictureContentDetector.Refined finish(JSONObject call,float[] maskLogits,float[] edgeLogits,
            float[] mask,float[] edge,PictureQuadFit.Result fit,
            PictureQuadFit.ConstrainedRecoveryResult recovery,PictureContentDetector.Refined returned) {
        if(call==null)return returned;var a=ACTIVE.get();
        try {
            String id=call.getString("id");
            call.put("mask_logits",a.payload(id+"-mask-logits.f32",floats(FloatBuffer.wrap(maskLogits))))
                    .put("edge_logits",a.payload(id+"-edge-logits.f32",floats(FloatBuffer.wrap(edgeLogits))))
                    .put("mask_probability",a.payload(id+"-mask-probability.f32",floats(FloatBuffer.wrap(mask))))
                    .put("edge_probability",a.payload(id+"-edge-probability.f32",floats(FloatBuffer.wrap(edge))))
                    .put("fit",fit(fit)).put("returned_fit",refined(returned));
            if(recovery!=null)call.put("recovery",new JSONObject().put("border_contacts",recovery.borderContacts())
                    .put("extrapolated_support",fit(recovery.extrapolatedSupport()))
                    .put("constrained_support",fit(recovery.constrainedSupport()))
                    .put("line_offset_delta",recovery.lineOffsetDelta()==null?JSONObject.NULL:numbers(recovery.lineOffsetDelta())));
            a.currentCall=null;return returned; // Same actual object; decisions and evidence are not replaced.
        }catch(IOException|JSONException error){throw new AssertionError("Observation outputs failed",error);}
    }
    static void globalReturns(List<PictureContentDetector.Refined> primary,
                              List<PictureContentDetector.Refined> fallback,List<PictureContentDetector.Refined> verifier) {
        var a=ACTIVE.get();if(a==null)return;
        try {a.page.put("global_refined_before_arbitration",new JSONObject()
                .put("primary",refinedList(primary)).put("fallback",refinedList(fallback)).put("verifier",refinedList(verifier)));}
        catch(JSONException error){throw new AssertionError("Observation branches failed",error);}
    }
    private static Object refinedList(List<PictureContentDetector.Refined> list)throws JSONException {
        if(list==null)return JSONObject.NULL;var out=new JSONArray();
        for(var r:list)out.put(refined(r));return out;
    }
    private static Object refined(PictureContentDetector.Refined r)throws JSONException {
        if(r==null)return JSONObject.NULL;
        var out=new JSONObject().put("source_quad",quad(r.quad())).put("reason",r.reason())
                .put("fit_reasons",new JSONArray(r.fitReasons())).put("border_contacts",r.borderContacts())
                .put("content_supported",r.contentSupported());
        var first=r.firstMask();out.put("first_mask",first==null?JSONObject.NULL:new JSONObject()
                .put("empty",first.emptyMask()).put("active_pixels",first.activePixels())
                .put("maximum",finite(first.maximum())).put("finite",first.finite()));
        var f=r.fitEvidence();out.put("fit_evidence",f==null?JSONObject.NULL:new JSONObject()
                .put("omitted",finite(f.omitted())).put("expansion",finite(f.expansion()))
                .put("weak_edge",f.weakEdge()).put("aspect",finite(f.aspect())));return out;
    }
    private static Object fit(PictureQuadFit.Result r)throws JSONException {
        if(r==null)return JSONObject.NULL;
        return new JSONObject().put("grid_quad",quad(r.quad())).put("reasons",new JSONArray(r.reasons()))
                .put("dominance",finite(r.dominance())).put("expansion",finite(r.expansion())).put("omitted",finite(r.omitted()))
                .put("side_continuity",numbers(r.sideContinuity())).put("side_support",numbers(r.sideSupport()));
    }
    private static byte[] floats(FloatBuffer source) {
        var view=source.asReadOnlyBuffer();view.rewind();
        var bytes=ByteBuffer.allocate(view.remaining()*Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        while(view.hasRemaining())bytes.putInt(Float.floatToRawIntBits(view.get()));return bytes.array();
    }
    private JSONObject payload(String name,byte[] bytes)throws IOException,JSONException {
        entry(name,bytes);return new JSONObject().put("entry",name).put("bytes",bytes.length).put("sha256",hash(bytes));
    }
    private void entry(String name,byte[] bytes)throws IOException {
        zip.putNextEntry(new ZipEntry(name));zip.write(bytes);zip.closeEntry();
    }
    private static String hash(byte[] bytes) {
        try {var out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes)){
            out.append(Character.forDigit((b>>>4)&15,16));out.append(Character.forDigit(b&15,16));}return out.toString();}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private static JSONArray numbers(double[] values)throws JSONException {
        var out=new JSONArray();for(double value:values)out.put(finite(value));return out;
    }
    private static Object quad(PictureQuadFit.Vertex[] points)throws JSONException {
        if(points==null)return JSONObject.NULL;var out=new JSONArray();
        for(var p:points)out.put(new JSONArray().put(finite(p.x())).put(finite(p.y())));return out;
    }
    private static Object finite(double value){return Double.isFinite(value)?value:JSONObject.NULL;}
    public void close()throws IOException {
        ACTIVE.remove();
        try(var unused=zip){
            try {entry("trace.json",new JSONObject().put("schema_version",1)
                    .put("input_layout","LEf32 NCHW[1,4,384,384]")
                    .put("map_layout","LEf32 NCHW[1,1,96,96]")
                    .put("component_layout","LEi32 row-major[96,96], actual OpenCV labels")
                    .put("coordinates","upright source pixel centers; input 0..383; grid=(input-1.5)/4")
                    .put("pages",pages).toString().getBytes(StandardCharsets.UTF_8));}
            catch(JSONException error){throw new IOException(error);}
        }
    }
}
