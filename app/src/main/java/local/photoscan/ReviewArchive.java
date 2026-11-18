package local.photoscan;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** A portable snapshot of the current saved album, assembled on the album state worker. */
final class ReviewArchive {
    static final String AUTHORITY_SUFFIX = ".review-files";
    private static final String ROOT = "review-archives";
    private static final int PREVIEW_EDGE = 480;
    private static final long PREVIEW_SPACE = 2L * PREVIEW_EDGE * PREVIEW_EDGE;

    private ReviewArchive() {}

    static File create(Context context, ScanSession album, String albumId, String issueNote, IntConsumer progress)
            throws Exception {
        return create(context, album, albumId, issueNote, null, progress);
    }

    static File create(Context context, ScanSession album, String albumId, String issueNote,
                       String physicalBookId, IntConsumer progress) throws Exception {
        if (physicalBookId != null && (!album.collection || album.physicalBook(physicalBookId) == null)) {
            throw new IOException("That physical album is unavailable.");
        }
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < album.sources.size(); i++) {
            if (physicalBookId == null || physicalBookId.equals(album.sources.get(i).physicalBookId())) indices.add(i);
        }
        if (indices.isEmpty()) throw new IOException("There are no saved scans in this export.");
        if (album.pendingDelete || !album.pendingCamera.isEmpty()) {
            throw new IOException("Finish the current album work before exporting a review ZIP.");
        }
        File staging = directory(context, "staging");
        File shared = directory(context, "shared");
        checkSpace(album, indices, staging);
        String token = ScanSession.newId();
        File temporary = new File(staging, token + ".zip.part");
        File complete = new File(shared, "PhotoAlbumScan-review-" + token + ".zip");
        try {
            writeArchive(context, album, albumId, issueNote, physicalBookId, indices, progress, temporary);
            checkInterrupted();
            if (!temporary.renameTo(complete)) throw new IOException("Could not finish writing the review ZIP.");
            return complete;
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                Log.w("PhotoScanReview", "Could not remove an incomplete review ZIP.");
            }
        }
    }

    static Uri shareUri(Context context, File complete) {
        return FileProvider.getUriForFile(context, context.getPackageName() + AUTHORITY_SUFFIX, complete);
    }

    static void discardInterrupted(Context context) {
        File staging = new File(new File(context.getCacheDir(), ROOT), "staging");
        File[] files = staging.listFiles((folder, name) -> name.matches("[0-9a-f-]{36}\\.zip\\.part"));
        if (files == null) return;
        for (File file : files) {
            if (!file.delete()) Log.w("PhotoScanReview", "Could not remove an interrupted review ZIP.");
        }
    }

    private static File directory(Context context, String name) throws IOException {
        File directory = new File(new File(context.getCacheDir(), ROOT), name);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not open storage for the review ZIP.");
        }
        return directory;
    }

    private static void checkSpace(ScanSession album, List<Integer> indices, File staging) throws IOException {
        long remaining = staging.getUsableSpace() - (2L << 20);
        for (int index : indices) {
            File original = album.original(album.sources.get(index));
            if (!original.isFile() || original.length() == 0) {
                throw new IOException("A saved original is missing or empty. The review ZIP was not created.");
            }
            remaining -= original.length() + PREVIEW_SPACE;
            if (remaining < 0) throw new IOException("Not enough storage for the review ZIP.");
        }
    }

    private static void writeArchive(Context context, ScanSession album, String albumId, String issueNote,
                                     String physicalBookId, List<Integer> indices, IntConsumer progress, File temporary)
            throws Exception {
        byte[] buffer = new byte[128 * 1024];
        JSONObject manifest = new JSONObject()
                .put("schema_version", 3)
                .put("created_utc", Instant.now().toString())
                .put("build", installedBuild(context, buffer))
                .put("album", new JSONObject().put("id", albumId).put("name", album.album)
                        .put("date_default", album.albumDate).put("pending_import_count", album.imports.size())
                        .put("expected_count_default", album.expectedCount)
                        .put("mode", album.collection ? "training_collection" : "normal")
                        .put("issue_note", issueNote == null ? "" : issueNote.substring(0, Math.min(issueNote.length(), 2000))))
                .put("coordinate_convention", new JSONObject()
                        .put("frame", "upright_source_after_exif_orientation")
                        .put("units", "normalized image edges, x right and y down, both 0 to 1")
                        .put("corner_order", "saved rectification order: top-left, top-right, bottom-right, bottom-left")
                        .put("rotation", "clockwise quarter turns applied after rectification"))
                .put("history", new JSONObject().put("per_page_detection_build", "recorded when available; null for unknown")
                        .put("per_crop_manual_or_detected", "not recorded")
                        .put("raw_detector_candidates", "not stored")
                        .put("scope", "Current saved crops and statuses; installed build identity is not historical provenance."));
        JSONArray pages = new JSONArray();
        StringBuilder html = startHtml(album.album, manifest.getJSONObject("album").getString("issue_note"));
        try (FileOutputStream output = new FileOutputStream(temporary);
             ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(output))) {
            zip.setLevel(Deflater.NO_COMPRESSION);
            for (int i = 0; i < indices.size(); i++) {
                checkInterrupted();
                int index = indices.get(i);
                ScanSession.Source source = album.sources.get(index);
                JSONObject page = writePage(zip, album, source, i + 1, buffer)
                        .put("album_page_number", index + 1);
                pages.put(page);
                appendPageHtml(html, page);
                progress.accept(i + 1);
            }
            checkInterrupted();
            manifest.put("pages", pages).put("export_receipts", receipts(album));
            if (album.collection) manifest.put("collection", collectionSummary(album, physicalBookId, pages));
            zip.setLevel(Deflater.DEFAULT_COMPRESSION);
            writeText(zip, "manifest.json", manifest.toString(2));
            writeText(zip, "index.html", html.append("</main></body></html>").toString());
            zip.finish();
            zip.flush();
            output.getFD().sync();
        }
    }

    private static JSONObject collectionSummary(ScanSession album, String physicalBookId, JSONArray pages)
            throws Exception {
        JSONArray books = new JSONArray();
        for (ScanSession.PhysicalBook book : album.physicalBooks) {
            if (physicalBookId == null || physicalBookId.equals(book.id())) {
                books.put(new JSONObject().put("id", book.id()).put("label", book.label()));
            }
        }
        var sets = new LinkedHashMap<String, JSONObject>();
        for (int i = 0; i < pages.length(); i++) {
            JSONObject page = pages.getJSONObject(i);
            JSONObject metadata = page.getJSONObject("capture").getJSONObject("collection");
            JSONObject set = captureSet(sets, metadata);
            if (metadata.optBoolean("unavailable_alternates")) set.put("unavailable_alternates", true);
            set.getJSONArray("members").put(new JSONObject().put("source_id", page.getString("id"))
                    .put("ordinal", metadata.getInt("ordinal")).put("role", metadata.getString("role")));
        }
        for (JSONObject attempt : album.collectionUnstored) {
            JSONObject metadata = attempt.getJSONObject("collection");
            if (physicalBookId == null || physicalBookId.equals(metadata.getString("physical_book_id"))) {
                captureSet(sets, metadata).getJSONArray("unsaved_attempts").put(attempt);
            }
        }
        JSONArray captureSets = new JSONArray();
        for (JSONObject set : sets.values()) {
            JSONArray members = set.getJSONArray("members");
            JSONArray failures = set.getJSONArray("unsaved_attempts");
            int planned = set.getInt("planned_count");
            JSONArray roles = new JSONArray();
            for (int ordinal = 1; ordinal <= planned; ordinal++) {
                String status = ordinal > 1 && set.optBoolean("unavailable_alternates")
                        ? "unavailable" : "not_attempted_or_interrupted";
                String role = ScanMetadata.plannedRole(planned, ordinal);
                for (int i = 0; i < failures.length(); i++) {
                    JSONObject attempt = failures.getJSONObject(i);
                    JSONObject metadata = attempt.getJSONObject("collection");
                    if (metadata.getInt("ordinal") == ordinal) {
                        status = attempt.getString("capture_outcome");
                        role = metadata.getString("role");
                    }
                }
                for (int i = 0; i < members.length(); i++) {
                    JSONObject member = members.getJSONObject(i);
                    if (member.getInt("ordinal") == ordinal) {
                        status = "saved";
                        role = member.getString("role");
                    }
                }
                roles.put(new JSONObject().put("ordinal", ordinal).put("role", role).put("status", status));
            }
            captureSets.put(set.put("actual_count", members.length())
                    .put("complete", members.length() == planned).put("planned_members", roles));
        }
        return new JSONObject().put("export_scope", physicalBookId == null ? "whole_collection" : "physical_book")
                .put("selected_physical_book_id", ScanMetadata.known(physicalBookId))
                .put("physical_books", books).put("capture_sets", captureSets);
    }

    private static JSONObject captureSet(LinkedHashMap<String, JSONObject> sets, JSONObject metadata)
            throws Exception {
        String sceneId = metadata.getString("scene_id");
        JSONObject set = sets.get(sceneId);
        if (set == null) {
            set = new JSONObject().put("scene_id", sceneId)
                    .put("physical_book_id", metadata.getString("physical_book_id"))
                    .put("planned_count", metadata.getInt("planned_count"))
                    .put("book_scene_ordinal", metadata.getInt("book_scene_ordinal"))
                    .put("sampling_policy", metadata.getString("sampling_policy"))
                    .put("members", new JSONArray()).put("unsaved_attempts", new JSONArray());
            sets.put(sceneId, set);
        }
        return set;
    }

    private static JSONObject installedBuild(Context context, byte[] buffer) throws Exception {
        JSONArray models = new JSONArray();
        String[] names = context.getAssets().list("");
        if (names != null) {
            Arrays.sort(names);
            for (String name : names) {
                if (!name.endsWith(".onnx")) continue;
                try (InputStream input = context.getAssets().open(name)) {
                    models.put(new JSONObject().put("asset", name).put("sha256", copyAndHash(input, null, buffer)));
                }
            }
        }
        return new JSONObject().put("application_id", context.getPackageName())
                .put("version_name", BuildConfig.VERSION_NAME).put("version_code", BuildConfig.VERSION_CODE)
                .put("android_api", android.os.Build.VERSION.SDK_INT).put("bundled_onnx_assets", models)
                .put("current_review_policy", "CropReviewPolicy in PhotoAlbumScan " + BuildConfig.VERSION_NAME)
                .put("detection_settings", ScanMetadata.detectorSettings());
    }

    private static JSONObject writePage(ZipOutputStream zip, ScanSession album, ScanSession.Source source,
                                        int number, byte[] buffer) throws Exception {
        File original = album.original(source);
        var bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(original.getAbsolutePath(), bounds);
        int orientation = ExifInterface.ORIENTATION_UNDEFINED;
        JSONObject exposureExif = new JSONObject();
        try {
            ExifInterface exif = new ExifInterface(original);
            orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED);
            for (String tag : List.of(ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
                    ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
                    ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE)) {
                exposureExif.put(tag, ScanMetadata.known(exif.getAttribute(tag)));
            }
        } catch (IOException | RuntimeException unavailable) {
            Log.d("PhotoScanReview", "Original has no readable EXIF orientation.", unavailable);
        }
        boolean swap = orientation >= ExifInterface.ORIENTATION_TRANSPOSE && orientation <= ExifInterface.ORIENTATION_ROTATE_270;
        int width = swap ? bounds.outHeight : bounds.outWidth;
        int height = swap ? bounds.outWidth : bounds.outHeight;

        String key = String.format(Locale.ROOT, "page-%04d", number);
        String originalPath = "originals/" + key + "." + extension(bounds.outMimeType);
        zip.putNextEntry(new ZipEntry(originalPath));
        String digest;
        try (InputStream input = java.nio.file.Files.newInputStream(original.toPath())) {
            digest = copyAndHash(input, zip, buffer);
        }
        zip.closeEntry();

        String state = source.collectionOnly ? "collection_only" : source.pending() ? "pending" : source.failed ? "failed" : "processed";
        JSONObject capture = source.captureMetadata;
        JSONObject detection = source.detectionMetadata;
        Object expected = capture == null ? JSONObject.NULL : ScanMetadata.known(capture.opt("expected_count"));
        Object found = detection == null ? JSONObject.NULL : ScanMetadata.known(detection.opt("detected_count"));
        Object mismatch = expected instanceof Number n && n.intValue() > 0 && found instanceof Number count
                ? n.intValue() != count.intValue() : JSONObject.NULL;
        JSONObject page = new JSONObject().put("id", source.id).put("number", number)
                .put("capture_origin", capture == null ? "legacy_unknown" : capture.optString("origin", "legacy_unknown"))
                .put("capture", ScanMetadata.known(capture)).put("detection", ScanMetadata.known(detection))
                .put("expected_count", expected).put("automatic_detected_count", found)
                .put("current_crop_count", source.crops.size()).put("count_mismatch", mismatch)
                .put("exif_exposure", exposureExif)
                .put("processing_state", state).put("revision", source.revision)
                .put("collection_only", source.collectionOnly)
                .put("source_kind", source.retainedFromSourceId.isEmpty() ? "original_scan" : "retained_crop")
                .put("original_scan_available", source.retainedFromSourceId.isEmpty())
                .put("retained_from_source_id", source.retainedFromSourceId.isEmpty()
                        ? JSONObject.NULL : source.retainedFromSourceId)
                .put("recorded_review_required", source.reviewRequired)
                .put("unoutlined_count", source.unoutlined).put("recorded_warnings", new JSONArray(source.warnings))
                .put("historical_detector_identity", detection == null ? "unknown"
                        : detection.getJSONObject("build").getString("version_name"))
                .put("original_path", originalPath).put("original_sha256", digest).put("original_bytes", original.length())
                .put("original_mime_type", bounds.outMimeType == null ? JSONObject.NULL : bounds.outMimeType)
                .put("raw_width", dimension(bounds.outWidth)).put("raw_height", dimension(bounds.outHeight))
                .put("upright_width", dimension(width)).put("upright_height", dimension(height))
                .put("exif_orientation", orientation)
                .put("crops", crops(album, source));
        writePreview(zip, original, "previews/" + key + ".jpg", page);
        return page;
    }

    private static Object dimension(int value) { return value > 0 ? value : JSONObject.NULL; }

    private static String extension(String mime) {
        if (mime == null) return "img";
        return switch (mime) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/heic", "image/heif" -> "heic";
            case "image/avif" -> "avif";
            case "image/gif" -> "gif";
            case "image/bmp" -> "bmp";
            case "image/tiff" -> "tiff";
            case "image/x-adobe-dng" -> "dng";
            default -> "img";
        };
    }

    private static JSONArray crops(ScanSession album, ScanSession.Source source) throws Exception {
        JSONArray crops = new JSONArray();
        for (int i = 0; i < source.crops.size(); i++) {
            ScanSession.Crop crop = source.crops.get(i);
            JSONArray corners = new JSONArray();
            for (float coordinate : crop.corners) corners.put((double) coordinate);
            crops.put(new JSONObject().put("id", crop.id).put("number", i + 1)
                    .put("corners_upright_normalized", corners)
                    .put("clockwise_turns_after_rectification", crop.turns)
                    .put("caption", crop.caption).put("date_note", crop.dateNote)
                    .put("effective_date", album.effectiveDate(crop))
                    .put("orientation_pending", crop.orientationPending)
                    .put("recorded_review_reason", crop.reviewReason)
                    .put("check_crop_visible", !CropReviewPolicy.legacyReason(crop.reviewReason).isEmpty())
                    .put("geometry_origin", "not recorded"));
        }
        return crops;
    }

    private static JSONArray receipts(ScanSession album) throws Exception {
        JSONArray entries = new JSONArray();
        for (ScanSession.Receipt receipt : album.receipts) {
            entries.put(new JSONObject().put("ticket", receipt.ticket).put("signature", receipt.signature)
                    .put("filename", receipt.filename).put("saved_reference_recorded", !receipt.uri.isEmpty()));
        }
        return entries;
    }

    private static void writePreview(ZipOutputStream zip, File original, String path, JSONObject page) throws Exception {
        Bitmap bitmap;
        try {
            bitmap = ImageEngine.preview(original, PREVIEW_EDGE);
        } catch (IOException | RuntimeException | OutOfMemoryError unavailable) {
            page.put("preview_path", JSONObject.NULL);
            return;
        }
        try {
            zip.putNextEntry(new ZipEntry(path));
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 78, zip)) throw new IOException("Could not write an album preview.");
            zip.closeEntry();
            page.put("preview_path", path).put("preview_width", bitmap.getWidth()).put("preview_height", bitmap.getHeight());
        } finally {
            bitmap.recycle();
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Review ZIP export was interrupted.");
    }

    private static String copyAndHash(InputStream input, ZipOutputStream output, byte[] buffer) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        for (int count; (count = input.read(buffer)) != -1;) {
            checkInterrupted();
            if (output != null) output.write(buffer, 0, count);
            hash.update(buffer, 0, count);
        }
        return hex(hash.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            result.append(Character.forDigit((item >>> 4) & 15, 16)).append(Character.forDigit(item & 15, 16));
        }
        return result.toString();
    }

    private static void writeText(ZipOutputStream zip, String path, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static StringBuilder startHtml(String name, String issueNote) {
        StringBuilder html = new StringBuilder("""
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>PhotoAlbumScan review</title><style>
                body{font:16px system-ui,sans-serif;margin:1.2rem;color:#132827;background:#f3f7f6}
                main{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,21rem),1fr));gap:1rem}
                article{padding:.8rem;background:#fff;border:1px solid #d6e1df;border-radius:.75rem}
                h2,p{margin:.35rem 0}.frame{position:relative}.frame img{display:block;width:100%;height:auto}
                svg{position:absolute;inset:0;width:100%;height:100%}
                polygon{fill:none;stroke:#35ffe0;stroke-width:2;vector-effect:non-scaling-stroke}
                .flag{stroke:#ffd371}text{fill:#fff;stroke:#142827;stroke-width:3;paint-order:stroke;font-weight:bold}
                .note{white-space:pre-wrap;overflow-wrap:anywhere}
                </style></head><body><h1>PhotoAlbumScan review</h1>
                """);
        html.append("<p>").append(TextUtils.htmlEncode(name)).append("</p>");
        if (!issueNote.isEmpty()) html.append("<p class=\"note\">").append(TextUtils.htmlEncode(issueNote)).append("</p>");
        return html.append("<p>Current saved crop outlines. Amber marks crops flagged for review. ")
                .append("See manifest.json for coordinates, notes and archive details.</p><main>");
    }

    private static void appendPageHtml(StringBuilder html, JSONObject page) throws Exception {
        JSONArray crops = page.getJSONArray("crops");
        html.append("<article><h2>Scan ").append(page.getInt("number")).append("</h2><p>")
                .append(page.getString("processing_state"))
                .append(" &middot; Current crops: ").append(crops.length())
                .append(" &middot; Automatic: ").append(page.isNull("automatic_detected_count") ? "unknown" : page.optInt("automatic_detected_count"))
                .append(" &middot; Expected: ").append(page.isNull("expected_count") ? "unknown"
                        : page.optInt("expected_count") == 0 ? "Auto" : page.optInt("expected_count"));
        if (page.optBoolean("count_mismatch")) html.append(" &middot; Count mismatch");
        html.append("</p>");
        if (page.isNull("preview_path")) {
            html.append("<p>Preview unavailable. Inspect the original scan.</p></article>");
            return;
        }
        int width = page.getInt("preview_width"), height = page.getInt("preview_height");
        html.append("<div class=\"frame\"><img loading=\"lazy\" alt=\"Upright scan preview\" src=\"")
                .append(page.getString("preview_path")).append("\" width=\"").append(width)
                .append("\" height=\"").append(height).append("\"><svg aria-hidden=\"true\" viewBox=\"0 0 ")
                .append(width).append(' ').append(height).append("\">");
        for (int i = 0; i < crops.length(); i++) {
            JSONObject crop = crops.getJSONObject(i);
            JSONArray corners = crop.getJSONArray("corners_upright_normalized");
            html.append(crop.getBoolean("check_crop_visible") ? "<polygon class=\"flag\" points=\"" : "<polygon points=\"");
            for (int j = 0; j < 8; j += 2) {
                html.append(Math.round(corners.getDouble(j) * width)).append(',')
                        .append(Math.round(corners.getDouble(j + 1) * height)).append(' ');
            }
            long x = Math.max(2, Math.min(width - 18, Math.round(corners.getDouble(0) * width) + 4));
            long y = Math.max(15, Math.min(height - 2, Math.round(corners.getDouble(1) * height) + 17));
            html.append("\"/><text x=\"").append(x).append("\" y=\"").append(y).append("\">")
                    .append(crop.getInt("number")).append("</text>");
        }
        html.append("</svg></div></article>");
    }
}
