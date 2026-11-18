package local.photoscan;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

final class BatchExport {
    private static final Uri JPEG_COLLECTION = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    private static final String FOLDER = Environment.DIRECTORY_PICTURES + "/PhotoAlbumScan/";
    private static final String LEGACY_FOLDER = Environment.DIRECTORY_PICTURES + "/PhotoScan/";
    private final ContentResolver resolver;
    private final ScanSession batch;

    BatchExport(ContentResolver resolver, ScanSession batch) {
        this.resolver = resolver;
        this.batch = batch;
    }

    enum Result { SAVED, ALREADY_SAVED }

    Result export(ScanSession.Source source, ScanSession.Crop crop, int page, int number) throws Exception {
        String stem = filenameStem(page, number);
        String date = batch.effectiveDate(crop);
        String signature = signature(source, crop, stem, date);
        ScanSession.Receipt receipt = findReceipt(signature);
        if (receipt != null && recover(receipt)) return Result.ALREADY_SAVED;
        if (renumberedPublished(source, crop, stem, signature) != null) return Result.ALREADY_SAVED;
        if (receipt == null) {
            receipt = new ScanSession.Receipt(signature, ScanSession.newId(), availableFilename(stem));
            batch.receipts.add(receipt);
            batch.saveReceipt(receipt);
        }

        Bitmap corrected = ImageEngine.render(batch.original(source), crop.corners, crop.turns, 0f, 0);
        try {
            ContentValues row = new ContentValues();
            row.put(MediaStore.Images.Media.DISPLAY_NAME, ticketName(receipt));
            row.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            row.put(MediaStore.Images.Media.RELATIVE_PATH, FOLDER);
            row.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri output = resolver.insert(JPEG_COLLECTION, row);
            if (output == null) throw new IOException("Gallery could not reserve a JPEG.");
            receipt.uri = output.toString();
            batch.saveReceipt(receipt);

            try (OutputStream bytes = resolver.openOutputStream(output, "w")) {
                if (bytes == null || !corrected.compress(Bitmap.CompressFormat.JPEG, 95,
                        JpegNotes.embed(bytes, crop.caption, date))) {
                    throw new IOException("Could not write the JPEG.");
                }
            }

            ContentValues finished = new ContentValues();
            finished.put(MediaStore.Images.Media.DISPLAY_NAME, receipt.filename);
            finished.put(MediaStore.Images.Media.IS_PENDING, 0);
            if (resolver.update(output, finished, null, null) != 1 || rowState(output) != 0) {
                throw new IOException("Gallery could not publish the JPEG.");
            }
            return Result.SAVED;
        } catch (Exception failure) {
            try {
                if (recover(receipt)) return Result.SAVED;
            } catch (Exception cleanupError) {
                failure.addSuppressed(cleanupError);
            }
            throw failure;
        } finally {
            corrected.recycle();
        }
    }

    String location(Uri uri) {
        try (Cursor row = resolver.query(uri, new String[]{MediaStore.Images.Media.RELATIVE_PATH},
                null, null, null)) {
            if (row != null && row.moveToFirst()) {
                String path = row.getString(0);
                if (path != null && !path.isEmpty()) return "Gallery · " + path.replaceFirst("/$", "");
            }
        } catch (RuntimeException unavailable) {
            android.util.Log.w("PhotoScanExport", "Could not read the saved JPEG location", unavailable);
        }
        return "Gallery";
    }

    void clearInterrupted() throws Exception {
        for (ScanSession.Receipt receipt : batch.receipts) recover(receipt);
    }

    Uri publishedUri(ScanSession.Source source, ScanSession.Crop crop, int page, int number) throws IOException {
        String stem = filenameStem(page, number);
        String signature = signature(source, crop, stem, batch.effectiveDate(crop));
        ScanSession.Receipt receipt = findReceipt(signature);
        if (receipt != null && !receipt.uri.isEmpty()) {
            Uri uri = Uri.parse(receipt.uri);
            if (rowState(uri) == 0) return uri;
        }
        return renumberedPublished(source, crop, stem, signature);
    }

    java.util.List<ScanSession.Receipt> aliasesForRetainedCrops(ScanSession.Source original, int page,
                                                           java.util.List<ScanSession.Source> retained) {
        var aliases = new java.util.ArrayList<ScanSession.Receipt>();
        for (int i = 0; i < retained.size(); i++) {
            try {
                Uri previous = publishedUri(original, original.crops.get(i), page, i + 1);
                if (previous == null) continue;
                String uri = previous.toString();
                for (ScanSession.Receipt prior : batch.receipts) {
                    if (!uri.equals(prior.uri)) continue;
                    ScanSession.Source source = retained.get(i);
                    ScanSession.Crop crop = source.crops.get(0);
                    String replacement = signature(source, crop, filenameStem(page + i, 1), batch.effectiveDate(crop));
                    var alias = new ScanSession.Receipt(replacement, ScanSession.newId(), prior.filename);
                    alias.uri = uri;
                    aliases.add(alias);
                    break;
                }
            } catch (IOException | RuntimeException unavailable) {
                android.util.Log.w("PhotoScanExport", "Could not check an existing export for a retained crop.", unavailable);
            }
        }
        return aliases;
    }

    private Uri renumberedPublished(ScanSession.Source source, ScanSession.Crop crop, String stem, String signature)
            throws IOException {
        String idPrefix = source.id + "/" + crop.id + "/";
        String albumPrefix = safeAlbum(batch.album) + "_Page";
        String editSuffix = signature.substring(idPrefix.length() + stem.length());
        for (ScanSession.Receipt old : batch.receipts) {
            if (old.uri.isEmpty() || !old.signature.startsWith(idPrefix + albumPrefix)) continue;
            int endOfStem = old.signature.indexOf('/', idPrefix.length());
            if (endOfStem < 0 || !old.signature.substring(endOfStem).equals(editSuffix)) continue;
            String positions = old.signature.substring(idPrefix.length() + albumPrefix.length(), endOfStem);
            if (!positions.matches("[0-9]+_Photo[0-9]+")) continue;
            Uri uri = Uri.parse(old.uri);
            if (rowState(uri) == 0) return uri;
        }
        return null;
    }

    private ScanSession.Receipt findReceipt(String signature) {
        for (ScanSession.Receipt receipt : batch.receipts) {
            if (receipt.signature.equals(signature)) return receipt;
        }
        return null;
    }

    private String filenameStem(int page, int number) {
        return String.format(Locale.ROOT, "%s_Page%03d_Photo%d", safeAlbum(batch.album), page, number);
    }

    // A committed row can survive a process stop immediately after publish.
    // An unfinished row can exist before its URI reached the session manifest.
    private boolean recover(ScanSession.Receipt receipt) throws Exception {
        if (!receipt.uri.isEmpty()) {
            Uri saved = Uri.parse(receipt.uri);
            int state = rowState(saved);
            if (state == 0) return true;
            if (state == 1) resolver.delete(saved, null, null);
            receipt.uri = "";
            batch.saveReceipt(receipt);
        }
        Uri abandoned = findName(ticketName(receipt));
        if (abandoned != null && rowState(abandoned) == 1) resolver.delete(abandoned, null, null);
        return false;
    }

    private int rowState(Uri uri) throws IOException {
        try (Cursor cursor = resolver.query(MediaStore.setIncludePending(uri),
                new String[]{MediaStore.Images.Media.IS_PENDING}, null, null, null)) {
            if (cursor == null) throw new IOException("Gallery could not verify the JPEG.");
            return cursor.moveToFirst() ? cursor.getInt(0) : -1;
        }
    }

    private Uri findName(String filename) throws IOException {
        try (Cursor cursor = resolver.query(MediaStore.setIncludePending(JPEG_COLLECTION),
                new String[]{MediaStore.Images.Media._ID},
                MediaStore.Images.Media.DISPLAY_NAME + "=? AND " + MediaStore.Images.Media.RELATIVE_PATH + " IN (?,?)",
                new String[]{filename, FOLDER, LEGACY_FOLDER}, null)) {
            if (cursor == null) throw new IOException("Gallery could not check existing filenames.");
            return cursor.moveToFirst() ? ContentUris.withAppendedId(JPEG_COLLECTION, cursor.getLong(0)) : null;
        }
    }

    private String availableFilename(String stem) throws IOException {
        String filename = stem + ".jpg";
        int revision = 2;
        while (findName(filename) != null) filename = stem + "_v" + revision++ + ".jpg";
        return filename;
    }

    private String signature(ScanSession.Source source, ScanSession.Crop crop, String stem, String date) {
        StringBuilder signature = new StringBuilder(source.id).append('/').append(crop.id)
                .append('/').append(stem).append('/').append(crop.turns).append("/0");
        for (float coordinate : crop.corners) signature.append('/').append(Float.floatToIntBits(coordinate));
        // Older exact-date exports had a blank clock and need a new revision for sortable EXIF.
        if (PhotoDate.isCalendarDate(date)) signature.append("/date-v2");
        if (!crop.caption.isEmpty() || !date.isEmpty()) {
            signature.append("/notes/").append(crop.caption.length()).append('/')
                    .append(crop.caption).append('/').append(date);
        }
        return signature.toString();
    }

    private static String ticketName(ScanSession.Receipt receipt) {
        return "PhotoScan-pending-" + receipt.ticket + ".jpg";
    }

    private static String safeAlbum(String name) {
        String cleaned = name.replaceAll("[^\\p{L}\\p{N}_-]+", "_").replaceAll("^_+|_+$", "");
        return cleaned.isEmpty() ? "Album" : cleaned;
    }
}
