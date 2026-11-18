package local.photoscan;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class ScanSession implements AutoCloseable {
    static final int MAX_CROPS = 6;
    final File originals;
    final File incoming;
    private final BatchStore store;
    private final Map<String, Integer> storedPages = new HashMap<>();
    private final Map<String, Integer> storedImports = new HashMap<>();
    private final Set<String> storedReceipts = new HashSet<>();
    final ArrayList<Source> sources = new ArrayList<>();
    final ArrayList<Receipt> receipts = new ArrayList<>();
    final ArrayList<Import> imports = new ArrayList<>();
    String album = "Album";
    String albumDate = "";
    String pendingCamera = "";
    int expectedCount;
    boolean collection;
    boolean collectionSingleOnly;
    final Map<String, Integer> collectionSceneCounts = new HashMap<>();

    int nextCollectionSceneOrdinal() { return collectionSceneCounts.getOrDefault(physicalBookId, 0) + 1; }

    void recordCollectionScene(JSONObject metadata) {
        collectionSceneCounts.merge(metadata.optString("physical_book_id"),
                metadata.optInt("book_scene_ordinal"), Math::max);
    }
    String physicalBookId = "";
    final ArrayList<PhysicalBook> physicalBooks = new ArrayList<>();
    final ArrayList<JSONObject> collectionUnstored = new ArrayList<>();

    record PhysicalBook(String id, String label) {}

    PhysicalBook physicalBook(String id) {
        for (PhysicalBook book : physicalBooks) if (book.id().equals(id)) return book;
        return null;
    }

    void addPhysicalBook(String label) {
        var book = new PhysicalBook(newId(), label);
        physicalBooks.add(book);
        physicalBookId = book.id();
    }
    long captureOrder;
    long completionOrder;
    JSONObject pendingCapture;
    String retakeSource = "";
    int page = 0;
    int selection = 0;
    boolean review;
    boolean editing;
    boolean originalsFirst = true;
    boolean pendingDelete;
    PageRemoval pageRemoval;

    record PageRemoval(String originalId, java.util.List<String> replacementIds) {}

    static final class Source {
        final String id;
        final ArrayList<Crop> crops = new ArrayList<>();
        boolean detected;
        boolean failed;
        boolean collectionOnly;
        boolean reviewRequired;
        String retainedFromSourceId = "";
        int unoutlined;
        final ArrayList<String> warnings = new ArrayList<>();
        long revision;
        JSONObject captureMetadata;
        JSONObject detectionMetadata;
        long queuedElapsed = -1;
        Source(String id) { this.id = id; }
        boolean pending() { return !collectionOnly && !detected && !failed; }
        JSONObject collectionCapture() {
            return captureMetadata == null ? null : captureMetadata.optJSONObject("collection");
        }
        String physicalBookId() {
            JSONObject metadata = collectionCapture();
            return metadata == null ? "" : metadata.optString("physical_book_id");
        }
    }

    static final class Crop {
        final String id;
        float[] corners;
        int turns;
        int reflection;
        String caption = "";
        String dateNote = "";
        boolean orientationPending;
        String reviewReason = "";
        Crop(String id, float[] corners) {
            this.id = id;
            this.corners = corners;
        }
    }

    static final class Receipt {
        final String signature;
        final String ticket;
        final String filename;
        String uri = "";
        Receipt(String signature, String ticket, String filename) {
            this.signature = signature;
            this.ticket = ticket;
            this.filename = filename;
        }
    }

    static final class Import {
        final String id;
        final String uri;
        Import(String id, String uri) {
            this.id = id;
            this.uri = uri;
        }
    }

    ScanSession(Context context) throws Exception {
        this(context.getFilesDir());
    }

    ScanSession(File root) throws Exception {
        originals = directory(new File(root, "originals"));
        incoming = directory(new File(root, "incoming"));
        AtomicFile manifest = new AtomicFile(new File(root, "batch.json"));
        store = new BatchStore(new File(root, "batch.db"));
        try {
            JSONObject data = store.read();
            if (data == null) {
                if (manifest.getBaseFile().exists() || new File(root, "batch.json.bak").exists()) {
                    read(new JSONObject(new String(manifest.readFully(), StandardCharsets.UTF_8)));
                }
                // A settings row is the migration marker and commits with every imported row.
                save();
            } else {
                read(data);
                for (int i = 0; i < sources.size(); i++) storedPages.put(sources.get(i).id, i);
                for (int i = 0; i < imports.size(); i++) storedImports.put(imports.get(i).id, i);
                for (Receipt receipt : receipts) storedReceipts.add(receipt.signature);
            }
            // A database commit is authoritative. Keep the old bytes for recovery if a
            // migration issue is discovered, including after an interrupted archive move.
            File legacy = manifest.getBaseFile();
            File archived = new File(root, "batch.migrated.json");
            if (legacy.isFile() && !archived.exists() && !legacy.renameTo(archived)) {
                android.util.Log.w("PhotoScanState", "Could not archive the legacy batch manifest.");
            }
        } catch (Exception failure) {
            store.close();
            throw failure;
        }
    }

    private void read(JSONObject data) throws Exception {
        album = data.getString("album");
        albumDate = data.optString("albumDate", "");
        pendingCamera = data.optString("pendingCamera");
        expectedCount = ScanMetadata.expectedCount(data.optInt("expectedCount"));
        collection = data.optBoolean("collection");
        collectionSingleOnly = data.optBoolean("collectionSingleOnly");
        physicalBookId = data.optString("physicalBookId");
        JSONArray books = data.optJSONArray("physicalBooks");
        if (books != null) for (int i = 0; i < books.length(); i++) {
            JSONObject book = books.getJSONObject(i);
            String id = requireId(book.getString("id"));
            physicalBooks.add(new PhysicalBook(id, book.getString("label")));
            collectionSceneCounts.put(id, Math.max(0, book.optInt("sceneCount")));
        }
        pendingCapture = data.optJSONObject("pendingCapture");
        JSONArray unstored = data.optJSONArray("collectionUnstored");
        if (unstored != null) for (int i = 0; i < unstored.length(); i++) {
            collectionUnstored.add(unstored.getJSONObject(i));
        }
        captureOrder = data.optLong("captureOrder");
        completionOrder = data.optLong("completionOrder");
        if (!pendingCamera.isEmpty()) requireId(pendingCamera);
        retakeSource = data.optString("retakeSource");
        if (!retakeSource.isEmpty()) requireId(retakeSource);
        review = data.optBoolean("review");
        editing = review && data.optBoolean("editing", true);
        originalsFirst = data.optBoolean("originalsFirst", true);
        pendingDelete = data.optBoolean("pendingDelete");
        JSONObject removal = data.optJSONObject("pageRemoval");
        if (removal != null) {
            JSONArray ids = removal.getJSONArray("replacementIds");
            var replacements = new ArrayList<String>();
            for (int i = 0; i < ids.length(); i++) replacements.add(requireId(ids.getString(i)));
            pageRemoval = new PageRemoval(requireId(removal.getString("originalId")), java.util.List.copyOf(replacements));
        }
        page = data.optInt("page");
        selection = data.optInt("selection");
        JSONArray pages = data.getJSONArray("sources");
        for (int i = 0; i < pages.length(); i++) {
            JSONObject entry = pages.getJSONObject(i);
            Source source = new Source(requireId(entry.getString("id")));
            source.detected = entry.optBoolean("detected");
            source.failed = entry.optBoolean("failed");
            source.collectionOnly = entry.optBoolean("collectionOnly");
            source.reviewRequired = entry.optBoolean("reviewRequired");
            source.retainedFromSourceId = entry.optString("retainedFromSourceId", "");
            if (!source.retainedFromSourceId.isEmpty()) requireId(source.retainedFromSourceId);
            source.unoutlined = entry.optInt("unoutlined");
            JSONArray warnings = entry.optJSONArray("warnings");
            if (warnings != null) for (int w = 0; w < warnings.length(); w++) source.warnings.add(warnings.getString(w));
            source.revision = entry.optLong("revision");
            source.captureMetadata = entry.optJSONObject("captureMetadata");
            source.detectionMetadata = entry.optJSONObject("detectionMetadata");
            JSONArray crops = entry.getJSONArray("crops");
            for (int j = 0; j < crops.length(); j++) {
                JSONObject item = crops.getJSONObject(j);
                JSONArray coordinates = item.getJSONArray("corners");
                float[] corners = new float[8];
                for (int k = 0; k < corners.length; k++) corners[k] = (float) coordinates.getDouble(k);
                if (!ImageEngine.validQuad(corners)) continue;
                Crop crop = new Crop(requireId(item.getString("id")), corners);
                crop.turns = item.optInt("turns") & 3;
                crop.reflection = Math.max(0, Math.min(100, item.optInt("reflection")));
                crop.caption = item.optString("caption", "");
                crop.dateNote = item.optString("dateNote", "");
                crop.orientationPending = item.optBoolean("orientationPending");
                crop.reviewReason = item.optString("reviewReason");
                source.crops.add(crop);
            }
            // Legacy pages with hand-edited crops must never re-enter detection.
            if (entry.optLong("revision", -1) == -1 && !source.crops.isEmpty()) source.detected = true;
            sources.add(source);
        }
        JSONArray records = data.optJSONArray("receipts");
        if (records != null) {
            for (int i = 0; i < records.length(); i++) {
                JSONObject item = records.getJSONObject(i);
                Receipt receipt = new Receipt(item.getString("signature"), requireId(item.getString("ticket")), item.getString("filename"));
                receipt.uri = item.optString("uri");
                receipts.add(receipt);
            }
        }
        JSONArray queued = data.optJSONArray("imports");
        if (queued != null) {
            for (int i = 0; i < queued.length(); i++) {
                JSONObject item = queued.getJSONObject(i);
                imports.add(new Import(requireId(item.getString("id")), item.getString("uri")));
            }
        }
        clamp();
    }

    boolean originalReviewEmpty() {
        return editing && originalsFirst && page == -1;
    }

    int nearestOriginalPage(int start) {
        int first = Math.max(0, Math.min(start, sources.size()));
        for (int i = first; i < sources.size(); i++) {
            if (AlbumRedetection.isEligible(sources.get(i))) return i;
        }
        for (int i = first - 1; i >= 0; i--) {
            if (AlbumRedetection.isEligible(sources.get(i))) return i;
        }
        return -1;
    }

    void clamp() {
        if (originalReviewEmpty()) {
            selection = 0;
            return;
        }
        page = Math.max(0, Math.min(page, sources.size() - 1));
        selection = Math.max(0, Math.min(selection, sources.isEmpty() ? 0 : sources.get(page).crops.size() - 1));
    }

    Source current() { return sources.isEmpty() || page < 0 ? null : sources.get(page); }

    Crop selected() {
        Source current = current();
        return current == null || current.crops.isEmpty() ? null : current.crops.get(selection);
    }

    File original(Source source) { return new File(originals, source.id); }
    File incoming(String id) { return new File(incoming, id + ".jpg"); }
    String effectiveDate(Crop crop) { return crop.dateNote.isEmpty() ? albumDate : crop.dateNote; }

    boolean commitDetection(PageProcessingQueue.Request request, PageProcessingQueue.Detection result)
            throws IOException, JSONException {
        Source target = null;
        for (Source source : sources) if (source.id.equals(request.sourceId)) {
            target = source;
            break;
        }
        if (!request.stillApplies(target)) return false;
        target.detected = result != null;
        target.failed = result == null;
        target.detectionMetadata = ScanMetadata.detection(request, result, ++completionOrder);
        if (result != null) {
            target.reviewRequired = result.reviewRequired;
            target.unoutlined = result.unoutlined;
            target.warnings.clear();
            target.warnings.addAll(result.warnings);
            for (int i = 0; i < result.corners.size(); i++) {
                Crop crop = new Crop(newId(), result.corners.get(i).clone());
                crop.reviewReason = result.cropReasons.get(i);
                crop.orientationPending = false;
                target.crops.add(crop);
            }
        }
        saveChanges(target);
        return true;
    }

    boolean commitOrientation(PageProcessingQueue.Request request, String cropId, Integer turns)
            throws IOException, JSONException {
        for (Source source : sources) {
            if (!request.sameRevision(source)) continue;
            for (Crop crop : source.crops) {
                if (!crop.id.equals(cropId) || !crop.orientationPending) continue;
                crop.orientationPending = false;
                if (turns != null && turns >= 0 && turns < 4) crop.turns = turns;
                saveChanges(source);
                return true;
            }
        }
        return false;
    }

    void saveManualEdit(Source source) throws IOException, JSONException {
        source.detected = true;
        source.failed = false;
        source.revision++;
        saveChanges(source);
    }

    void save() throws IOException, JSONException {
        saveChanges(sources.toArray(new Source[0]), receipts.toArray(new Receipt[0]));
    }

    void saveChanges(Source... changed) throws IOException, JSONException {
        saveChanges(changed, new Receipt[0]);
    }

    void saveReceipt(Receipt receipt) throws JSONException {
        store.put("receipts", receipt.signature, null, receiptData(receipt));
        storedReceipts.add(receipt.signature);
    }

    private void saveChanges(Source[] changed, Receipt[] changedReceipts) throws JSONException {
        JSONArray books = new JSONArray();
        for (PhysicalBook book : physicalBooks) {
            books.put(new JSONObject().put("id", book.id()).put("label", book.label())
                    .put("sceneCount", collectionSceneCounts.getOrDefault(book.id(), 0)));
        }
        JSONObject data = new JSONObject()
                .put("collection", collection).put("collectionSingleOnly", collectionSingleOnly)
                .put("physicalBookId", physicalBookId).put("physicalBooks", books)
                .put("collectionUnstored", new JSONArray(collectionUnstored))
                .put("album", album).put("albumDate", albumDate)
                .put("pendingCamera", pendingCamera).put("retakeSource", retakeSource)
                .put("expectedCount", expectedCount).put("pendingCapture", pendingCapture)
                .put("captureOrder", captureOrder).put("completionOrder", completionOrder)
                .put("page", page).put("selection", selection).put("review", review).put("editing", editing)
                .put("originalsFirst", originalsFirst)
                .put("pendingDelete", pendingDelete)
                .put("pageRemoval", pageRemoval == null ? null : new JSONObject()
                        .put("originalId", pageRemoval.originalId())
                        .put("replacementIds", new JSONArray(pageRemoval.replacementIds())));
        Set<Source> dirty = new HashSet<>();
        java.util.Collections.addAll(dirty, changed);
        Set<Receipt> dirtyReceipts = new HashSet<>();
        java.util.Collections.addAll(dirtyReceipts, changedReceipts);
        Map<String, Integer> pages = new HashMap<>();
        Map<String, Integer> queued = new HashMap<>();
        Set<String> records = new HashSet<>();
        store.db.beginTransaction();
        try {
            store.settings(data);
            for (int i = 0; i < sources.size(); i++) {
                Source source = sources.get(i);
                pages.put(source.id, i);
                Integer previous = storedPages.get(source.id);
                if (previous == null || dirty.contains(source)) store.put("pages", source.id, i, pageData(source));
                else if (previous != i) store.move("pages", source.id, i);
            }
            for (String id : storedPages.keySet()) if (!pages.containsKey(id)) store.remove("pages", id);
            for (int i = 0; i < imports.size(); i++) {
                Import request = imports.get(i);
                queued.put(request.id, i);
                Integer previous = storedImports.get(request.id);
                if (previous == null) store.put("imports", request.id, i,
                        new JSONObject().put("id", request.id).put("uri", request.uri));
                else if (previous != i) store.move("imports", request.id, i);
            }
            for (String id : storedImports.keySet()) if (!queued.containsKey(id)) store.remove("imports", id);
            for (Receipt receipt : receipts) {
                records.add(receipt.signature);
                if (!storedReceipts.contains(receipt.signature) || dirtyReceipts.contains(receipt)) {
                    store.put("receipts", receipt.signature, null, receiptData(receipt));
                }
            }
            for (String id : storedReceipts) if (!records.contains(id)) store.remove("receipts", id);
            store.db.setTransactionSuccessful();
        } finally {
            store.db.endTransaction();
        }
        storedPages.clear(); storedPages.putAll(pages);
        storedImports.clear(); storedImports.putAll(queued);
        storedReceipts.clear(); storedReceipts.addAll(records);
    }

    private static JSONObject pageData(Source source) throws JSONException {
        JSONArray crops = new JSONArray();
        for (Crop crop : source.crops) {
            JSONArray coordinates = new JSONArray();
            for (float value : crop.corners) coordinates.put((double) value);
            crops.put(new JSONObject().put("id", crop.id).put("corners", coordinates)
                    .put("turns", crop.turns).put("reflection", crop.reflection)
                    .put("caption", crop.caption).put("dateNote", crop.dateNote)
                    .put("orientationPending", crop.orientationPending)
                    .put("reviewReason", crop.reviewReason));
        }
        return new JSONObject().put("id", source.id).put("detected", source.detected)
                .put("collectionOnly", source.collectionOnly)
                .put("failed", source.failed).put("revision", source.revision)
                .put("reviewRequired", source.reviewRequired).put("unoutlined", source.unoutlined)
                .put("retainedFromSourceId", source.retainedFromSourceId.isEmpty() ? null : source.retainedFromSourceId)
                .put("captureMetadata", source.captureMetadata).put("detectionMetadata", source.detectionMetadata)
                .put("warnings", new JSONArray(source.warnings)).put("crops", crops);
    }

    private static JSONObject receiptData(Receipt receipt) throws JSONException {
        return new JSONObject().put("signature", receipt.signature).put("ticket", receipt.ticket)
                .put("filename", receipt.filename).put("uri", receipt.uri);
    }

    @Override public void close() { store.close(); }

    static String newId() { return UUID.randomUUID().toString(); }

    private static File directory(File path) throws IOException {
        if (!path.isDirectory() && !path.mkdirs()) throw new IOException("Could not create batch storage.");
        return path;
    }

    private static String requireId(String id) throws IOException {
        if (!id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) throw new IOException("Invalid batch file ID.");
        return id;
    }
}
