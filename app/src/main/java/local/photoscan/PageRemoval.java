package local.photoscan;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Replaces a saved page and then releases its source pixels. The journal makes file cleanup retryable. */
final class PageRemoval {
    private static final float[] FULL_FRAME = {0, 0, 1, 0, 1, 1, 0, 1};

    private PageRemoval() {}

    static boolean remove(ScanSession batch, int pageIndex, boolean keepCrops, ContentResolver resolver)
            throws Exception {
        if (!recover(batch)) throw new IOException("Finish storage cleanup for the previous page removal first.");
        ScanSession.Source original = batch.sources.get(pageIndex);
        List<ScanSession.Source> retained = keepCrops ? retainedSources(original) : List.of();
        if (keepCrops && retained.isEmpty()) throw new IOException("There are no cropped photos to keep.");
        batch.pageRemoval = new ScanSession.PageRemoval(original.id, retained.stream().map(source -> source.id)
                .collect(java.util.stream.Collectors.toList()));
        batch.saveChanges();

        try {
            for (int i = 0; i < retained.size(); i++) {
                retainPixels(batch, original, original.crops.get(i), retained.get(i));
            }
        } catch (Exception | LinkageError | OutOfMemoryError failure) {
            recover(batch);
            throw failure;
        }

        if (keepCrops) {
            batch.receipts.addAll(new BatchExport(resolver, batch).aliasesForRetainedCrops(original, pageIndex + 1, retained));
        }
        batch.sources.remove(pageIndex);
        batch.sources.addAll(pageIndex, retained);
        batch.page = batch.nearestOriginalPage(pageIndex + retained.size());
        batch.selection = 0;
        batch.editing = true;
        batch.originalsFirst = true;
        batch.review = true;

        // The original file is still intact if this transaction fails or never runs.
        batch.saveChanges(retained.toArray(new ScanSession.Source[0]));
        return recover(batch);
    }

    private static List<ScanSession.Source> retainedSources(ScanSession.Source original) throws org.json.JSONException {
        var retained = new ArrayList<ScanSession.Source>();
        for (ScanSession.Crop crop : original.crops) {
            var standalone = new ScanSession.Source(ScanSession.newId());
            standalone.detected = true;
            standalone.captureMetadata = ScanMetadata.derived("retained_crop", original);
            standalone.retainedFromSourceId = original.id;
            standalone.reviewRequired = !crop.reviewReason.isEmpty();
            var saved = new ScanSession.Crop(crop.id, FULL_FRAME.clone());
            saved.caption = crop.caption;
            saved.dateNote = crop.dateNote;
            saved.reviewReason = crop.reviewReason;
            standalone.crops.add(saved);
            retained.add(standalone);
        }
        return retained;
    }

    private static void retainPixels(ScanSession batch, ScanSession.Source original, ScanSession.Crop crop,
                                     ScanSession.Source retained) throws IOException {
        Bitmap bitmap = ImageEngine.render(batch.original(original), crop.corners, crop.turns, 0f, 0);
        try {
            File staging = batch.incoming(retained.id);
            try (var output = new FileOutputStream(staging)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                    throw new IOException("Could not save the retained photo.");
                }
            }
            ScanController.commitOriginal(staging, batch.original(retained));
        } finally {
            bitmap.recycle();
        }
    }

    static boolean recover(ScanSession batch) {
        ScanSession.PageRemoval pending = batch.pageRemoval;
        if (pending == null) return true;
        var referenced = new HashSet<String>();
        for (ScanSession.Source source : batch.sources) referenced.add(source.id);
        boolean committed = !referenced.contains(pending.originalId());
        try {
            if (committed) {
                Files.deleteIfExists(new File(batch.originals, pending.originalId()).toPath());
            }
            for (String replacement : pending.replacementIds()) {
                Files.deleteIfExists(batch.incoming(replacement).toPath());
                if (!committed && !referenced.contains(replacement)) {
                    Files.deleteIfExists(new File(batch.originals, replacement).toPath());
                }
            }
            ScanController.syncDirectory(batch.originals);
            ScanController.syncDirectory(batch.incoming);
            batch.pageRemoval = null;
            batch.saveChanges();
            return true;
        } catch (Exception failure) {
            batch.pageRemoval = pending;
            Log.w("PhotoScanState", "Page removal file cleanup will retry.", failure);
            return false;
        }
    }
}
