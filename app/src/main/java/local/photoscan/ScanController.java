package local.photoscan;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class ScanController {
    private static ScanController instance;

    static ScanController get(Context context) {
        if (instance == null) instance = new ScanController(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Executor worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final String FIRST_ALBUM = AlbumDeletion.FIRST_ALBUM;
    private ScanSession batch;
    private String activeAlbum = "";
    private boolean libraryVisible = true;
    private List<AlbumEntry> albumEntries = List.of();
    private List<RemovedCrop> undoCrops = List.of();

    record AlbumEntry(String id, String name, int pages) {}
    record RemovedCrop(String pageId, int index, int originalCount, ScanSession.Crop crop) {}
    private ScanActivity activity;
    private CaptureActivity cameraActivity;
    private volatile boolean cameraVisible;
    private boolean started;
    private boolean ready;
    private boolean busy;
    private final PageProcessingQueue processing;
    private PictureContentDetector pictureDetector;
    private String progress = "";
    private String message = "";
    private Screen screen;
    private Bitmap sourcePreview;
    private String sourcePreviewId = "";

    private ScanController(Context context) {
        this.context = context;
        processing = new PageProcessingQueue(worker, Executors.newSingleThreadExecutor(), this::detectPage);
    }

    record CaptureVariant(String sceneId, int ordinal, int plannedCount, String role,
                          String policy, boolean unavailableAlternates) {
        CaptureVariant(String sceneId, int ordinal, int plannedCount, String role) {
            this(sceneId, ordinal, plannedCount, role, plannedCount == 3 ? "forced_bracket" : "sampled_normal", false);
        }
    }

    record CaptureOptions(int expectedCount, Double requestedExposureEv, Integer requestedExposureIndex,
                          Integer appliedExposureIndex, Double appliedExposureEv, Double exposureStepEv,
                          CaptureVariant variant) {
        CaptureOptions(int expectedCount, Double requestedExposureEv, Integer requestedExposureIndex,
                       Integer appliedExposureIndex, Double appliedExposureEv, Double exposureStepEv) {
            this(expectedCount, requestedExposureEv, requestedExposureIndex, appliedExposureIndex,
                    appliedExposureEv, exposureStepEv, null);
        }
    }

    static final class CountSummary {
        final String sourceId;
        final int pageNumber;
        final int expectedCount;
        final Integer detectedCount;
        final String status;
        final boolean mismatch;

        CountSummary(ScanSession.Source source, int pageNumber) {
            sourceId = source.id;
            this.pageNumber = pageNumber;
            expectedCount = source.captureMetadata.optInt("expected_count");
            var detection = source.detectionMetadata;
            detectedCount = detection == null || detection.isNull("detected_count")
                    ? null : detection.optInt("detected_count");
            status = source.collectionOnly ? "collected" : detection == null ? (source.pending() ? "pending" : "unknown")
                    : detection.optString("outcome");
            mismatch = expectedCount > 0 && detectedCount != null && expectedCount != detectedCount;
        }
    }

    static final class Screen {
        final int expectedCount;
        final boolean collection;
        final boolean collectionSingleOnly;
        final int nextCollectionSceneOrdinal;
        final String physicalBookId;
        final String physicalBookLabel;
        final List<ScanSession.PhysicalBook> physicalBooks;
        final int queuedCount;
        final CountSummary latestScan;
        final CountSummary latestCompleted;
        final String album;
        String albumId;
        boolean libraryVisible;
        List<AlbumEntry> albums = List.of();
        int undoCount;
        final boolean cropNeedsReview;
        final String currentSourceId;
        final boolean retainedCropSource;
        final String albumDate;
        final String pending;
        final int page;
        final int pageCount;
        final int redetectablePages;
        final int retainedPageCount;
        final int selection;
        final boolean review;
        final boolean editing;
        final boolean originalsFirst;
        final boolean pendingDelete;
        final ArrayList<File> originals = new ArrayList<>();
        final ArrayList<PhotoTile> photos = new ArrayList<>();
        final int pagesWithoutCrops;
        final int pendingPages;
        final int failedPages;
        final int reviewPages;
        final int pendingRotations;
        final String pageStatus;
        final String caption;
        final String dateNote;
        final String effectiveDate;
        final ArrayList<float[]> corners = new ArrayList<>();
        final Bitmap source;
        final Bitmap corrected;

        Screen(ScanSession batch, Bitmap source, Bitmap corrected) {
            this(batch, source, corrected, true);
        }

        Screen(ScanSession batch, Bitmap source, Bitmap corrected, boolean includeReview) {
            album = batch == null ? "" : batch.album;
            albumDate = batch == null ? "" : batch.albumDate;
            pending = batch == null ? "" : batch.pendingCamera;
            page = batch == null ? -1 : batch.page;
            pageCount = batch == null ? 0 : batch.sources.size();
            collection = batch != null && batch.collection;
            collectionSingleOnly = collection && batch.collectionSingleOnly;
            nextCollectionSceneOrdinal = collection ? batch.nextCollectionSceneOrdinal() : 0;
            physicalBookId = collection ? batch.physicalBookId : "";
            var book = collection ? batch.physicalBook(physicalBookId) : null;
            physicalBookLabel = book == null ? "" : book.label();
            physicalBooks = collection ? List.copyOf(batch.physicalBooks) : List.of();
            redetectablePages = batch == null || collection ? 0
                    : (int) batch.sources.stream().filter(AlbumRedetection::isEligible).count();
            retainedPageCount = collection ? 0 : pageCount - redetectablePages;
            expectedCount = batch == null ? 0 : batch.expectedCount;
            CountSummary newest = null;
            CountSummary completed = null;
            long newestOrder = -1;
            long completedOrder = -1;
            int queued = 0;
            if (batch != null) for (int i = 0; i < batch.sources.size(); i++) {
                ScanSession.Source entry = batch.sources.get(i);
                if (entry.pending()) queued++;
                var capture = entry.captureMetadata;
                if (capture == null || !"camera".equals(capture.optString("origin"))) continue;
                long order = capture.optLong("capture_order");
                if (order >= newestOrder) {
                    newest = new CountSummary(entry, i + 1);
                    newestOrder = order;
                }
                if (entry.detectionMetadata == null) continue;
                long finished = entry.detectionMetadata.optLong("completion_order");
                if (finished >= completedOrder) {
                    completed = new CountSummary(entry, i + 1);
                    completedOrder = finished;
                }
            }
            queuedCount = queued;
            latestScan = newest;
            latestCompleted = completed;
            selection = batch == null ? 0 : batch.selection;
            review = batch != null && batch.review;
            editing = batch != null && batch.editing;
            originalsFirst = batch != null && batch.originalsFirst;
            pendingDelete = batch != null && batch.pendingDelete;
            int emptyPages = 0;
            int waitingPages = 0;
            int unavailablePages = 0;
            int warningPages = 0;
            int waitingRotations = 0;
            if (review && includeReview) {
                for (int i = 0; i < batch.sources.size(); i++) {
                    ScanSession.Source page = batch.sources.get(i);
                    File original = batch.original(page);
                    if (page.pending()) waitingPages++;
                    else if (page.failed) unavailablePages++;
                    else if (!page.collectionOnly && page.crops.isEmpty()) emptyPages++;
                    if (pageNeedsCheck(page)) warningPages++;
                    if (editing) originals.add(original);
                    else {
                        for (int j = 0; j < page.crops.size(); j++) {
                            ScanSession.Crop item = page.crops.get(j);
                            if (item.orientationPending) waitingRotations++;
                            photos.add(new PhotoTile(original, item, i + 1, j + 1));
                        }
                    }
                }
            }
            pagesWithoutCrops = emptyPages;
            pendingPages = waitingPages;
            failedPages = unavailablePages;
            reviewPages = warningPages;
            pendingRotations = waitingRotations;
            ScanSession.Source current = batch == null ? null : batch.current();
            currentSourceId = current == null ? "" : current.id;
            retainedCropSource = current != null && !current.retainedFromSourceId.isEmpty();
            pageStatus = current == null ? "" : current.collectionOnly ? "Original saved for training." : current.pending() ? "Finding photos on this page…"
                    : current.failed ? "Automatic detection failed. Add crops manually or retry."
                    : current.unoutlined == 1 ? "One candidate could not be outlined. Check this scan."
                    : current.unoutlined > 1 ? current.unoutlined + " candidates could not be outlined. Check this scan."
                    : pageNeedsCheck(current) ? "Check the photos on this scan." : "";
            if (current != null) for (ScanSession.Crop crop : current.crops) corners.add(crop.corners.clone());
            ScanSession.Crop crop = batch == null ? null : batch.selected();
            cropNeedsReview = crop != null && !CropReviewPolicy.legacyReason(crop.reviewReason).isEmpty();
            caption = crop == null ? "" : crop.caption;
            dateNote = crop == null ? "" : crop.dateNote;
            effectiveDate = crop == null ? "" : batch.effectiveDate(crop);
            this.source = source;
            this.corrected = corrected;
        }
    }

    private static boolean pageNeedsCheck(ScanSession.Source source) {
        return source.unoutlined > 0 || source.crops.size() > ScanSession.MAX_CROPS || source.warnings.contains("Many crop candidates; check this scan")
                || source.crops.stream().anyMatch(crop -> !CropReviewPolicy.legacyReason(crop.reviewReason).isEmpty());
    }

    static final class PhotoTile {
        final String id;
        final File original;
        final float[] corners;
        final int turns;
        final int page;
        final int number;
        final boolean needsCropReview;

        PhotoTile(File original, ScanSession.Crop crop, int page, int number) {
            id = crop.id;
            this.original = original;
            corners = crop.corners.clone();
            turns = crop.turns;
            this.page = page;
            this.number = number;
            needsCropReview = !CropReviewPolicy.legacyReason(crop.reviewReason).isEmpty();
        }
    }

    void attach(ScanActivity activity) {
        this.activity = activity;
        start();
    }

    void attachCamera(CaptureActivity activity) {
        cameraActivity = activity;
        cameraVisible = true;
        start();
    }

    void detachCamera(CaptureActivity activity) {
        if (cameraActivity == activity) {
            cameraActivity = null;
            cameraVisible = false;
            worker.execute(this::scheduleProcessing);
        }
    }

    private void start() {
        if (started) {
            notifyScreen();
            return;
        }
        started = true;
        run("Opening batch", () -> {
            AlbumDeletion storage = albumStorage();
            boolean cleaned = storage.cleanup();
            String savedAlbum = context.getSharedPreferences("albums", Context.MODE_PRIVATE)
                    .getString("active", FIRST_ALBUM);
            if (albumExists(storage, savedAlbum)) activeAlbum = savedAlbum;
            else if (storage.exists(FIRST_ALBUM)) activeAlbum = FIRST_ALBUM;
            AlbumRedetection.discardInterrupted(new File(context.getFilesDir(), "albums"));
            ReviewArchive.discardInterrupted(context);
            String cleanupNotice = cleaned ? "" : "Album removed. Private-file cleanup will retry when the app reopens.";
            if (activeAlbum.isEmpty()) {
                refreshAlbums();
                return cleanupNotice;
            }
            batch = new ScanSession(storage.root(activeAlbum));
            PageRemoval.recover(batch);
            refreshAlbums();
            if (batch.pendingDelete && !finishDeleteFiles()) return "Could not finish removing the album’s private files.";
            String imports = batch.imports.isEmpty() ? "" : completeImports();
            libraryVisible = !batch.originalReviewEmpty();
            try {
                new BatchExport(context.getContentResolver(), batch).clearInterrupted();
                return imports.isEmpty() ? cleanupNotice : imports;
            } catch (Exception unavailable) {
                return "Gallery could not check interrupted exports. Export JPEGs to retry.";
            }
        });
    }

    void detach(ScanActivity activity) {
        if (this.activity == activity) this.activity = null;
    }

    void redraw() { notifyScreen(); }

    void setExpectedCount(int count) {
        run("Saving capture settings", () -> {
            if (batch != null) {
                batch.expectedCount = ScanMetadata.expectedCount(count);
                batch.saveChanges();
            }
            return "";
        });
    }

    void reviewScan(String sourceId) {
        enqueue("Opening scan", () -> {
            if (batch == null) return "";
            int index = indexOfSource(batch, sourceId);
            if (index < 0 || !AlbumRedetection.isEligible(batch.sources.get(index))) return "That scan is unavailable.";
            libraryVisible = false;
            batch.review = true;
            batch.editing = true;
            batch.originalsFirst = true;
            batch.page = index;
            batch.selection = 0;
            batch.saveChanges();
            return "";
        });
    }

    void reserveCapture(boolean retake, Consumer<File> completion) {
        reserveCapture(retake, null, completion);
    }

    void reserveCapture(boolean retake, CaptureOptions options, Consumer<File> completion) {
        if (busy) {
            completion.accept(null);
            return;
        }
        File[] reserved = new File[1];
        enqueue("Preparing photo", () -> {
            if (batch == null) throw new IOException("Open an album before taking photos.");
            if (!batch.pendingCamera.isEmpty()) throw new IOException("Recover or discard the previous camera shot first.");
            String id = ScanSession.newId();
            if (batch.collection && retake) throw new IOException("Collection captures keep every original.");
            var settings = options == null
                    ? new CaptureOptions(batch.expectedCount, null, null, null, null, null) : options;
            var metadata = ScanMetadata.camera(settings, batch.captureOrder + 1);
            if (batch.collection) {
                metadata.put("collection", ScanMetadata.collectionCapture(batch, activeAlbum, settings.variant()));
            }
            File target = batch.incoming(id);
            if (!target.createNewFile()) throw new IOException("Could not prepare the camera file.");
            batch.captureOrder++;
            if (batch.collection) batch.recordCollectionScene(metadata.getJSONObject("collection"));
            batch.pendingCapture = metadata;
            batch.pendingCamera = id;
            batch.retakeSource = retake && batch.current() != null ? batch.current().id : "";
            try {
                batch.saveChanges();
            } catch (Exception failure) {
                batch.pendingCamera = "";
                batch.pendingCapture = null;
                batch.retakeSource = "";
                deleteFile(target);
                throw failure;
            }
            reserved[0] = target;
            return "";
        }, () -> completion.accept(reserved[0]));
    }

    void finishCamera(boolean keep) {
        finishCamera(keep, null);
    }

    void finishCamera(boolean keep, Consumer<File> completion) {
        finishCamera(keep, false, completion);
    }

    void finishCamera(boolean keep, boolean captureFailed, Consumer<File> completion) {
        File[] committed = new File[1];
        // The result can arrive while Android is recreating the Activity and the controller is loading.
        enqueue("Saving camera photo", () -> {
            if (batch == null) return "";
            String id = batch.pendingCamera;
            if (id.isEmpty()) return "";
            ScanSession.Source source = new ScanSession.Source(id);
            File cameraFile = batch.incoming(id);
            File original = batch.original(source);
            if (!keep) {
                if (batch.collection && batch.pendingCapture != null) {
                    batch.pendingCapture.put("capture_outcome", captureFailed ? "failed" : "discarded");
                    batch.collectionUnstored.add(batch.pendingCapture);
                }
                batch.pendingCamera = "";
                batch.pendingCapture = null;
                batch.retakeSource = "";
                batch.saveChanges();
                deleteFile(cameraFile);
                deleteFile(original);
                return "Camera shot discarded.";
            }

            boolean replacing = indexOfSource(batch, batch.retakeSource) >= 0;
            try {
                committed[0] = commitPendingCapture(batch);
            } catch (IOException invalid) {
                return "Could not add this camera photo. Recover it or discard the shot.";
            }
            return batch.collection ? "Collection original saved." : replacing ? "Page replaced." : "Camera photo added.";
        }, completion == null ? null : () -> completion.accept(committed[0]));
    }

    static File commitPendingCapture(ScanSession batch) throws Exception {
        String id = batch.pendingCamera;
        if (id.isEmpty()) return null;
        ScanSession.Source source = new ScanSession.Source(id);
        File original = batch.original(source);
        File captured = original.isFile() ? original : batch.incoming(id);
        validatePhoto(captured);
        commitOriginal(captured, original);

        source.captureMetadata = batch.pendingCapture;
        source.collectionOnly = batch.collection;
        if (source.captureMetadata != null) {
            source.captureMetadata.put("source_committed_at_ms", System.currentTimeMillis());
        }
        source.queuedElapsed = android.os.SystemClock.elapsedRealtime();
        int replace = indexOfSource(batch, batch.retakeSource);
        File replaced = replace < 0 ? null : batch.original(batch.sources.get(replace));
        if (replace >= 0) {
            batch.sources.set(replace, source);
            batch.page = replace;
            batch.selection = 0;
            batch.review = true;
            batch.editing = false;
        } else {
            batch.sources.add(source);
        }
        batch.pendingCamera = "";
        batch.pendingCapture = null;
        batch.retakeSource = "";
        batch.saveChanges(source);
        if (replaced != null) deleteFile(replaced);
        return original;
    }

    void importPhotos(List<Uri> uris) {
        enqueue("Importing photos", () -> {
            if (batch == null) throw new IOException("Open an album before importing photos.");
            if (batch.collection) throw new IOException("Use the camera to add originals to a training collection.");
            for (Uri uri : uris) {
                try {
                    context.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException temporaryPermission) {
                    // Some pickers only grant access for the current task.
                }
                batch.imports.add(new ScanSession.Import(ScanSession.newId(), uri.toString()));
            }
            batch.saveChanges();
            String imported = completeImports();
            if (batch.sources.isEmpty()) return imported;
            showAlbum();
            return imported;
        });
    }

    private String completeImports() throws Exception {
        int added = 0;
        int failed = 0;
        int total = batch.imports.size();
        while (!batch.imports.isEmpty()) {
            report("Importing photo " + (added + failed + 1) + " of " + total);
            ScanSession.Import request = batch.imports.get(0);
            Uri uri = Uri.parse(request.uri);
            ScanSession.Source source = new ScanSession.Source(request.id);
            File original = batch.original(source);
            File temporary = batch.incoming(source.id);
            try {
                if (original.isFile()) {
                    validateImportedPhoto(original);
                    commitOriginal(original, original);
                } else {
                    try (InputStream input = context.getContentResolver().openInputStream(uri);
                         FileOutputStream output = new FileOutputStream(temporary)) {
                        if (input == null) throw new IOException("Photo is unavailable.");
                        byte[] buffer = new byte[64 * 1024];
                        for (int bytes; (bytes = input.read(buffer)) != -1; ) output.write(buffer, 0, bytes);
                        output.getFD().sync();
                    }
                    validateImportedPhoto(temporary);
                    commitOriginal(temporary, original);
                }
                source.captureMetadata = ScanMetadata.source("import");
                source.queuedElapsed = android.os.SystemClock.elapsedRealtime();
                batch.sources.add(source);
                added++;
            } catch (IOException | RuntimeException | OutOfMemoryError unreadable) {
                deleteFile(temporary);
                deleteFile(original);
                failed++;
            }
            batch.imports.remove(0);
            batch.saveChanges();
            releaseImportPermission(uri);
        }
        return "Imported " + added + (added == 1 ? " photo." : " photos.")
                + (failed == 0 ? "" : " " + failed + " could not be opened.");
    }

    private void releaseImportPermission(Uri uri) {
        try {
            context.getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException temporaryPermission) {
            // A temporary picker grant has nothing to release.
        }
    }

    private static int indexOfSource(ScanSession batch, String id) {
        for (int i = 0; i < batch.sources.size(); i++) {
            if (batch.sources.get(i).id.equals(id)) return i;
        }
        return -1;
    }

    private AlbumDeletion albumStorage() throws IOException {
        return new AlbumDeletion(context.getFilesDir());
    }

    private static boolean albumExists(AlbumDeletion storage, String id) {
        try {
            return storage.exists(id);
        } catch (IOException invalid) {
            return false;
        }
    }

    private File albumRoot(String id) throws IOException {
        return albumStorage().root(id);
    }

    private void refreshAlbums() throws Exception {
        ArrayList<AlbumEntry> entries = new ArrayList<>();
        addAlbumEntry(entries, FIRST_ALBUM);
        File[] dirs = new File(context.getFilesDir(), "albums")
                .listFiles(directory -> directory.isDirectory() && !AlbumRedetection.isStaging(directory));
        if (dirs != null) for (File dir : dirs) {
            addAlbumEntry(entries, dir.getName());
        }
        entries.sort(Comparator.comparing(AlbumEntry::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(AlbumEntry::id));
        albumEntries = List.copyOf(entries);
    }

    private void addAlbumEntry(ArrayList<AlbumEntry> entries, String id) {
        try {
            if (!albumStorage().exists(id)) return;
            File file = new File(albumRoot(id), "batch.db");
            if (!file.isFile()) return;
            try (BatchStore store = new BatchStore(file)) {
                var settings = store.settings();
                if (settings != null) entries.add(new AlbumEntry(id, settings.getString("album"), store.pageCount()));
            }
        } catch (Exception error) {
            android.util.Log.w("PhotoScanState", "Could not list album " + id, error);
        }
    }

    void showLibrary() {
        run("Opening album library", () -> {
            libraryVisible = true;
            refreshAlbums();
            return "";
        });
    }

    void switchAlbum(String id) {
        run("Opening album", () -> {
            requireCameraIdle();
            if (batch == null || !id.equals(activeAlbum)) activateAlbum(id, null);
            showAlbum();
            return "";
        });
    }

    void createAlbum(String name) {
        run("Creating album", () -> {
            requireCameraIdle();
            activateAlbum(ScanSession.newId(), name);
            showAlbum();
            return "";
        });
    }

    void createCollection(String name) {
        run("Creating training collection", () -> {
            requireCameraIdle();
            activateAlbum(ScanSession.newId(), name, true);
            showAlbum();
            return "";
        });
    }

    void newPhysicalBook(String label) {
        run("Starting physical album", () -> {
            if (batch == null || !batch.collection) return "Open a training collection first.";
            if (!batch.pendingCamera.isEmpty()) return "Finish the current capture first.";
            String name = label == null ? "" : label.trim();
            if (name.isEmpty()) name = "Book " + (batch.physicalBooks.size() + 1);
            batch.addPhysicalBook(name);
            batch.saveChanges();
            return "";
        });
    }

    void switchPhysicalBook(String stableId) {
        run("Opening physical album", () -> {
            if (batch == null || !batch.collection || batch.physicalBook(stableId) == null) {
                return "That physical album is unavailable.";
            }
            if (!batch.pendingCamera.isEmpty()) return "Finish the current capture first.";
            batch.physicalBookId = stableId;
            batch.saveChanges();
            return "";
        });
    }

    void setCollectionSingleOnly(boolean enabled) {
        run("Saving collection settings", () -> {
            if (batch == null || !batch.collection) return "";
            batch.collectionSingleOnly = enabled;
            batch.saveChanges();
            return "";
        });
    }

    void markCollectionVariantUnavailable(CaptureVariant variant) {
        enqueue("Recording exposure set status", () -> {
            if (batch == null || !batch.collection || variant == null || variant.ordinal() == 1) return "";
            var metadata = ScanMetadata.collectionCapture(batch, activeAlbum, variant);
            if (metadata.optInt("book_scene_ordinal") >= batch.nextCollectionSceneOrdinal()) return "";
            batch.collectionUnstored.add(ScanMetadata.source("camera").put("collection", metadata)
                    .put("capture_outcome", "unavailable"));
            batch.saveChanges();
            return "";
        });
    }

    private void requireCameraIdle() throws IOException {
        if (cameraVisible || batch != null && !batch.pendingCamera.isEmpty()) {
            throw new IOException("Finish the current camera shot first.");
        }
    }

    boolean instanceBoundaryEnabled(){return InstanceBoundarySettings.enabled(context);}
    void setInstanceBoundaryEnabled(boolean enabled){InstanceBoundarySettings.set(context,enabled);}

    void redetectAlbum() {
        run("Copying original scans for re-detection", () -> {
            requireCameraIdle();
            if (batch == null) throw new IOException("Open an album first.");
            if (batch.collection) return "Training collections preserve originals for labeling.";
            String id = ScanSession.newId();
            int total = (int) batch.sources.stream().filter(AlbumRedetection::isEligible).count();
            AlbumRedetection.create(batch, albumRoot(id), copied -> {
                if (copied % 5 == 0 || copied == total) report("Copied " + copied + " of " + total + " original scans");
            });
            try {
                activateAlbum(id, null);
            } catch (Exception failure) {
                android.util.Log.w("PhotoScanState", "Could not open the completed re-detection album", failure);
                return "The copy is complete. Open it from Albums to continue re-detection.";
            }
            return "Re-detecting in a new album. Your original album is unchanged.";
        });
    }

    private void activateAlbum(String id, String newName) throws Exception {
        activateAlbum(id, newName, false);
    }

    private void activateAlbum(String id, String newName, boolean collection) throws Exception {
        File root = albumRoot(id);
        if (newName == null && !albumStorage().exists(id)) throw new IOException("Album could not be found.");
        if (newName != null && !root.mkdirs()) throw new IOException("Could not create album storage.");
        ScanSession next = new ScanSession(root);
        try {
            if (newName != null) {
                next.album = newName;
                next.collection = collection;
                if (collection) next.addPhysicalBook("Book 1");
                next.saveChanges();
            }
            if (!context.getSharedPreferences("albums", Context.MODE_PRIVATE).edit().putString("active", id).commit()) {
                throw new IOException("Could not save the current album.");
            }
        } catch (Exception failure) {
            next.close();
            throw failure;
        }
        if (batch != null) batch.close();
        batch = next;
        activeAlbum = id;
        undoCrops = List.of();
        sourcePreview = null;
        sourcePreviewId = "";
        libraryVisible = false;
        PageRemoval.recover(batch);
        if (batch.pendingDelete && !finishDeleteFiles()) throw new IOException("Could not finish deleting the album’s scans.");
        if (!batch.imports.isEmpty()) completeImports();
        new BatchExport(context.getContentResolver(), batch).clearInterrupted();
        refreshAlbums();
    }

    void openAlbum() {
        run("Opening album", () -> {
            showAlbum();
            return "";
        });
    }

    void cameraClosed(boolean review, boolean retake) {
        enqueue("Opening album", () -> {
            if (batch == null) return "";
            if (retake && !review) return "";
            if (!review && batch.sources.isEmpty()) return "";
            showAlbum();
            return "";
        });
    }

    private void showAlbum() throws Exception {
        if (batch == null) {
            libraryVisible = true;
            return;
        }
        libraryVisible = false;
        batch.review = true;
        batch.editing = false;
        batch.clamp();
        batch.saveChanges();
    }

    void editOriginals() {
        run("Opening original scans", () -> {
            batch.review = true;
            batch.editing = true;
            batch.originalsFirst = true;
            ScanSession.Source current = batch.current();
            if (current == null || !AlbumRedetection.isEligible(current)) {
                batch.page = batch.nearestOriginalPage(batch.page);
                batch.selection = 0;
            }
            batch.saveChanges();
            return "";
        });
    }

    void openPhoto(String cropId) {
        run("Opening photo", () -> {
            for (int i = 0; i < batch.sources.size(); i++) {
                ScanSession.Source source = batch.sources.get(i);
                for (int j = 0; j < source.crops.size(); j++) {
                    if (!source.crops.get(j).id.equals(cropId)) continue;
                    batch.page = i;
                    batch.selection = j;
                    batch.editing = true;
                    batch.originalsFirst = false;
                    batch.saveChanges();
                    return "";
                }
            }
            return "That photo is unavailable.";
        });
    }

    void selectPage(int page) {
        run("Opening page", () -> {
            batch.page = page;
            batch.selection = 0;
            batch.clamp();
            batch.saveChanges();
            return "";
        });
    }

    void selectCrop(int crop) {
        run("Opening crop", () -> {
            batch.selection = crop;
            batch.clamp();
            batch.saveChanges();
            return "";
        });
    }

    void addCrop() {
        run("Adding crop", () -> {
            if (batch.collection) return "";
            ScanSession.Source current = batch.current();
            if (current == null || current.crops.size() >= ScanSession.MAX_CROPS) return "";
            float inset = .14f;
            ScanSession.Crop crop = new ScanSession.Crop(ScanSession.newId(),
                    new float[]{inset, inset, 1 - inset, inset, 1 - inset, 1 - inset, inset, 1 - inset});
            current.crops.add(crop);
            batch.selection = current.crops.size() - 1;
            batch.saveManualEdit(current);
            return "Drag the corners to fit the photo.";
        });
    }

    void removeCrop() {
        run("Removing crop", () -> {
            ScanSession.Source source = batch.current();
            if (batch.selected() != null) {
                source.crops.remove(batch.selection);
                batch.clamp();
                batch.saveManualEdit(source);
            }
            if (!batch.originalsFirst) {
                showAlbum();
                return "Crop removed.";
            }
            batch.saveChanges();
            return "Crop removed.";
        });
    }

    void removePhotos(Set<String> selected) {
        Set<String> ids = Set.copyOf(selected);
        run("Removing photos", () -> {
            ArrayList<RemovedCrop> removed = new ArrayList<>();
            ArrayList<ScanSession.Source> changed = new ArrayList<>();
            for (ScanSession.Source source : batch.sources) {
                int count = removed.size();
                for (int i = 0; i < source.crops.size(); i++) {
                    ScanSession.Crop crop = source.crops.get(i);
                    if (ids.contains(crop.id)) removed.add(new RemovedCrop(source.id, i, source.crops.size(), crop));
                }
                if (removed.size() == count) continue;
                source.crops.removeIf(crop -> ids.contains(crop.id));
                source.revision++;
                changed.add(source);
            }
            if (removed.isEmpty()) return "";
            batch.clamp();
            batch.saveChanges(changed.toArray(new ScanSession.Source[0]));
            undoCrops = List.copyOf(removed);
            return removed.size() == 1 ? "Photo removed from album." : removed.size() + " photos removed from album.";
        });
    }

    void undoRemovePhotos() {
        run("Restoring photos", () -> {
            if (undoCrops.isEmpty()) return "";
            var additions = new java.util.HashMap<String, Integer>();
            for (RemovedCrop removed : undoCrops) {
                int index = indexOfSource(batch, removed.pageId());
                if (index < 0) return "The original scan has been replaced or removed.";
                ScanSession.Source source = batch.sources.get(index);
                int count = additions.merge(source.id, 1, Integer::sum);
                if (source.crops.size() + count > Math.max(ScanSession.MAX_CROPS, removed.originalCount())) {
                    return "Remove a crop to make room before restoring these photos.";
                }
            }
            var changed = new java.util.LinkedHashSet<ScanSession.Source>();
            for (RemovedCrop removed : undoCrops) {
                ScanSession.Source source = batch.sources.get(indexOfSource(batch, removed.pageId()));
                source.crops.add(Math.min(removed.index(), source.crops.size()), removed.crop());
                if (changed.add(source)) source.revision++;
            }
            batch.clamp();
            batch.saveChanges(changed.toArray(new ScanSession.Source[0]));
            undoCrops = List.of();
            return "Photos restored.";
        });
    }

    void markCropChecked() {
        run("Marking crop checked", () -> {
            ScanSession.Crop crop = batch.selected();
            if (crop == null || crop.reviewReason.isEmpty()) return "";
            crop.reviewReason = "";
            batch.saveChanges(batch.current());
            return "Crop marked checked.";
        });
    }

    void moveCorners(float[] corners) {
        float[] edited = corners.clone();
        run("Updating crop", () -> {
            ScanSession.Crop selected = batch.selected();
            if (selected == null) return "";
            if (!ImageEngine.validQuad(edited)) return "Keep the corners in order around the photo.";
            selected.corners = edited;
            selected.reviewReason = "";
            selected.orientationPending = false;
            batch.saveManualEdit(batch.current());
            return "";
        });
    }

    void rotate() {
        run("Rotating crop", () -> {
            ScanSession.Crop selected = batch.selected();
            if (selected != null) {
                selected.turns = (selected.turns + 1) % 4;
                selected.orientationPending = false;
                batch.saveManualEdit(batch.current());
            }
            return "";
        });
    }

    void rotateSelectedPhotos(Set<String> selected) {
        Set<String> ids = Set.copyOf(selected);
        run("Rotating photos", () -> {
            ArrayList<ScanSession.Source> changed = new ArrayList<>();
            for (ScanSession.Source source : batch.sources) {
                boolean dirty = false;
                for (ScanSession.Crop crop : source.crops) {
                    if (!ids.contains(crop.id)) continue;
                    crop.turns = (crop.turns + 1) % 4;
                    crop.orientationPending = false;
                    dirty = true;
                }
                if (dirty) {
                    source.revision++;
                    changed.add(source);
                }
            }
            if (!changed.isEmpty()) batch.saveChanges(changed.toArray(new ScanSession.Source[0]));
            return "Selected photos rotated.";
        });
    }

    void rename(String album) {
        run("Saving album name", () -> {
            batch.album = album.trim().isEmpty() ? "Album" : album.trim();
            batch.saveChanges();
            return "";
        });
    }

    void updateNotes(String caption, String dateNote) {
        run("Saving photo notes", () -> {
            ScanSession.Crop crop = batch.selected();
            if (crop != null) {
                crop.caption = caption.trim();
                crop.dateNote = dateNote.trim();
                batch.saveChanges(batch.current());
            }
            return "";
        });
    }

    void setSelectedPhotoDate(Set<String> selected, String date) {
        Set<String> ids = Set.copyOf(selected);
        run("Updating photo dates", () -> {
            ArrayList<ScanSession.Source> changed = new ArrayList<>();
            int count = 0;
            for (ScanSession.Source source : batch.sources) {
                boolean dirty = false;
                for (ScanSession.Crop crop : source.crops) {
                    if (!ids.contains(crop.id) || crop.dateNote.equals(date)) continue;
                    crop.dateNote = date;
                    dirty = true;
                    count++;
                }
                if (dirty) changed.add(source);
            }
            if (!changed.isEmpty()) batch.saveChanges(changed.toArray(new ScanSession.Source[0]));
            return count == 1 ? "Updated the date for 1 photo." : "Updated the date for " + count + " photos.";
        });
    }

    void retryFailedPages() {
        run("Retrying detection", () -> {
            ArrayList<ScanSession.Source> changed = new ArrayList<>();
            for (ScanSession.Source source : batch.sources) {
                if (!source.failed || !source.crops.isEmpty()) continue;
                source.failed = false;
                source.detectionMetadata = null;
                source.queuedElapsed = android.os.SystemClock.elapsedRealtime();
                source.revision++;
                changed.add(source);
            }
            batch.saveChanges(changed.toArray(new ScanSession.Source[0]));
            return "";
        });
    }

    void updateAlbumDate(String date) {
        run("Saving album date", () -> {
            batch.albumDate = date.trim();
            batch.saveChanges();
            return "";
        });
    }

    void deleteAlbum(String id) {
        run("Deleting album", () -> {
            requireCameraIdle();
            AlbumDeletion storage = albumStorage();
            if (!storage.exists(id)) {
                refreshAlbums();
                return "Album could not be found.";
            }
            boolean active = id.equals(activeAlbum);
            Set<String> abandonedImports = new HashSet<>();
            if (active && batch != null) {
                settleAlbumBeforeDeletion(batch, abandonedImports);
                batch.close();
                batch = null;
            } else {
                try (ScanSession owner = new ScanSession(storage.root(id))) {
                    settleAlbumBeforeDeletion(owner, abandonedImports);
                }
            }
            boolean synced = true;
            try {
                storage.retire(id);
            } catch (IOException failure) {
                if (storage.exists(id)) throw failure;
                // A rename or marker can succeed before directory sync fails. Keep the
                // album retired and retry durable private-file cleanup on startup.
                synced = false;
                android.util.Log.w("PhotoScanState", "Album retired, cleanup pending", failure);
            }
            if (active) {
                activeAlbum = "";
                context.getSharedPreferences("albums", Context.MODE_PRIVATE).edit().remove("active").apply();
                undoCrops = List.of();
                sourcePreview = null;
                sourcePreviewId = "";
            }
            libraryVisible = true;
            refreshAlbums();
            boolean removedFiles = synced && storage.cleanup();
            for (String uri : abandonedImports) {
                if (!anotherAlbumHasImport(storage, uri)) releaseImportPermission(Uri.parse(uri));
            }
            return removedFiles ? "Album deleted."
                    : "Album removed. Private-file cleanup will retry when the app reopens.";
        });
    }

    private void settleAlbumBeforeDeletion(ScanSession owner, Set<String> abandonedImports) throws Exception {
        // Published rows are preserved; only incomplete MediaStore writes are cleaned.
        new BatchExport(context.getContentResolver(), owner).clearInterrupted();
        for (ScanSession.Import request : owner.imports) abandonedImports.add(request.uri);
    }

    private boolean anotherAlbumHasImport(AlbumDeletion storage, String uri) {
        try {
            if (storage.exists(FIRST_ALBUM) && !new File(storage.root(FIRST_ALBUM), "batch.db").isFile()) {
                return true; // A legacy manifest may still own the grant.
            }
            for (AlbumEntry album : albumEntries) {
                File db = new File(storage.root(album.id()), "batch.db");
                if (!db.isFile()) return true;
                try (BatchStore store = new BatchStore(db)) {
                    if (store.hasPendingImport(uri)) return true;
                }
            }
            return false;
        } catch (Exception unavailable) {
            return true;
        }
    }

    private boolean finishDeleteFiles() throws Exception {
        boolean originalsDeleted = deleteContents(batch.originals);
        boolean incomingDeleted = deleteContents(batch.incoming);
        boolean legacyDeleted = true;
        for (String name : new String[]{"batch.migrated.json", "batch.json", "batch.json.bak", "batch.json.new"}) {
            File legacy = new File(albumRoot(activeAlbum), name);
            legacyDeleted &= !legacy.exists() || legacy.delete();
        }
        if (!originalsDeleted || !incomingDeleted || !legacyDeleted) return false;
        batch.pendingDelete = false;
        batch.saveChanges();
        return true;
    }

    void deleteCurrentPage(String expectedSourceId, boolean keepCrops) {
        run(keepCrops ? "Saving photos before removing the scan" : "Removing the scan", () -> {
            ScanSession.Source source = batch.current();
            if (source == null || !source.id.equals(expectedSourceId) || cameraVisible
                    || batch.pendingDelete || !batch.pendingCamera.isEmpty()) {
                return "The scan changed. Reopen it before deleting.";
            }
            boolean cleaned = PageRemoval.remove(batch, batch.page, keepCrops, context.getContentResolver());
            undoCrops = undoCrops.stream().filter(removed -> !removed.pageId().equals(expectedSourceId))
                    .collect(java.util.stream.Collectors.toList());
            sourcePreview = null;
            sourcePreviewId = "";
            if (!cleaned) return "Scan removed from the album. Storage cleanup will retry when you reopen it.";
            return keepCrops ? "Scan deleted. Cropped photos kept." : "Scan and its crops deleted.";
        });
    }

    void exportReviewArchive(String issueNote) {
        exportReviewArchive(issueNote, null);
    }

    void exportReviewArchive(String issueNote, String physicalBookId) {
        if (busy) return;
        Uri[] readyArchive = new Uri[1];
        enqueue("Preparing review ZIP", () -> {
            if (batch == null) return "Open an album first.";
            int total = physicalBookId == null ? batch.sources.size() : (int) batch.sources.stream()
                    .filter(source -> physicalBookId.equals(source.physicalBookId())).count();
            File complete = ReviewArchive.create(context, batch, activeAlbum, issueNote, physicalBookId, count -> {
                if (count % 5 == 0 || count == total) report("Archived " + count + " of " + total + " saved scans");
            });
            readyArchive[0] = ReviewArchive.shareUri(context, complete);
            return "Review ZIP ready.";
        }, () -> {
            if (readyArchive[0] != null && activity != null) activity.openReviewArchive(readyArchive[0]);
        });
    }

    void export(Set<String> selected, boolean share) {
        if (busy) return;
        Set<String> targets = selected == null ? null : new HashSet<>(selected);
        ArrayList<Uri> outputUris = new ArrayList<>();
        enqueue("Preparing export", () -> {
            BatchExport exporter = new BatchExport(context.getContentResolver(), batch);
            int saved = 0;
            int already = 0;
            int failed = 0;
            String firstFailure = "";
            for (int i = 0; i < batch.sources.size(); i++) {
                ScanSession.Source source = batch.sources.get(i);
                if (targets != null && source.crops.stream().noneMatch(crop -> targets.contains(crop.id))) continue;
                report("Exporting page " + (i + 1) + " of " + batch.sources.size());
                if (!source.detected) continue;
                boolean orientationChanged = false;
                for (ScanSession.Crop crop : source.crops) {
                    if (crop.orientationPending && (targets == null || targets.contains(crop.id))) {
                        crop.orientationPending = false;
                        orientationChanged = true;
                    }
                }
                if (orientationChanged) batch.saveChanges(source);
                for (int j = 0; j < source.crops.size(); j++) {
                    ScanSession.Crop crop = source.crops.get(j);
                    if (targets != null && !targets.contains(crop.id)) continue;
                    report("Exporting page " + (i + 1) + " of " + batch.sources.size() + " · Photo " + (j + 1));
                    try {
                        if (exporter.export(source, crop, i + 1, j + 1) == BatchExport.Result.SAVED) saved++;
                        else already++;
                        try {
                            Uri uri = exporter.publishedUri(source, crop, i + 1, j + 1);
                            if (uri != null) outputUris.add(uri);
                        } catch (IOException unavailable) {
                            android.util.Log.w("PhotoScanExport", "Could not open the saved JPEG", unavailable);
                        }
                    } catch (Exception error) {
                        failed++;
                        if (firstFailure.isEmpty()) firstFailure = describe(error);
                    }
                }
            }
            if (saved == 0 && already == 0 && failed == 0) {
                return batch.sources.stream().anyMatch(ScanSession.Source::pending)
                        ? "Pages are still processing. Ready photos can be exported as they appear."
                        : "Add at least one crop to export.";
            }
            String result = saved + " saved · " + already + " already saved · " + failed + " failed";
            if (saved > 0) result += "\nNew JPEGs · Pictures/PhotoAlbumScan";
            else if (!outputUris.isEmpty()) result += "\n" + exporter.location(outputUris.get(outputUris.size() - 1));
            if (failed > 0) result += "\n" + firstFailure;
            if (targets == null) {
                long waiting = batch.sources.stream().filter(ScanSession.Source::pending).count();
                if (waiting > 0) result += " · " + waiting + (waiting == 1 ? " page still processing" : " pages still processing");
            }
            return result;
        }, () -> {
            if (activity != null) {
                activity.showSavedPhotos(outputUris);
                if (share && !outputUris.isEmpty()) activity.openShare(outputUris);
            }
        });
    }

    private Screen buildScreen() throws Exception {
        if (libraryVisible || batch == null) return new Screen(batch, null, null, false);
        ScanSession.Source source = batch.current();
        if (cameraVisible) return new Screen(batch, null, null, false);
        if (!batch.editing || source == null) {
            sourcePreview = null;
            sourcePreviewId = "";
            return new Screen(batch, null, null);
        }
        if (!source.id.equals(sourcePreviewId)) {
            sourcePreview = ImageEngine.preview(batch.original(source), 1000);
            sourcePreviewId = source.id;
        }
        batch.clamp();
        ScanSession.Crop crop = batch.selected();
        if (crop == null) return new Screen(batch, sourcePreview, null);
        File original = batch.original(source);
        Bitmap corrected = ImageEngine.render(original, crop.corners, crop.turns, 0f, 720);
        return new Screen(batch, sourcePreview, corrected);
    }

    private PageProcessingQueue.Detection detectPage(File original) throws Exception {
        return detectPage(original,instanceBoundaryEnabled());
    }
    private PageProcessingQueue.Detection detectPage(File original,boolean instanceBoundaryRequested) throws Exception {
        if (pictureDetector == null) pictureDetector = new PictureContentDetector(context);
        pictureDetector.enableInstanceChooser(instanceBoundaryRequested);
        PictureContentDetector.Result result = pictureDetector.detect(original);
        var instanceDiagnostics=ScanMetadata.instanceBoundaryDiagnostics(pictureDetector,result,instanceBoundaryRequested);
        List<PictureContentDetector.Candidate> valid = new ArrayList<>();
        int unoutlined = 0;
        boolean overflow = result.overflow;
        Set<String> warnings = new java.util.LinkedHashSet<>();
        if (overflow) warnings.add("Many crop candidates; check this scan");
        for (PictureContentDetector.Candidate candidate : result.candidates) {
            if (candidate.omittedFromPhotos()) continue;
            if (candidate.corners != null && ImageEngine.validQuad(candidate.corners)) valid.add(candidate);
            else unoutlined++;
        }
        valid.sort(Comparator.<PictureContentDetector.Candidate>comparingInt(candidate ->
                        Math.round(center(candidate.orderCorners(), 1) * 10))
                .thenComparingDouble(candidate -> center(candidate.orderCorners(), 0))
                .thenComparingDouble(candidate -> center(candidate.orderCorners(), 1)));
        List<float[]> quads = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        for (PictureContentDetector.Candidate candidate : valid) {
            quads.add(candidate.corners);
            String reason = result.cropReviewReason(candidate, valid);
            reasons.add(reason);
            if (!reason.isEmpty()) warnings.add(reason);
        }
        return new PageProcessingQueue.Detection(quads, reasons,
                overflow || unoutlined > 0 || !warnings.isEmpty(), new ArrayList<>(warnings), unoutlined,
                ScanMetadata.boundaryDiagnostics(result),instanceDiagnostics);
    }

    private void scheduleProcessing() {
        if (batch == null || batch.pendingDelete || processing.inFlight()) return;
        for (ScanSession.Source source : batch.sources) {
            if (!source.pending()) continue;
            boolean requested=instanceBoundaryEnabled();
            PageProcessingQueue.Request request = new PageProcessingQueue.Request(source,batch.original(source),
                    requested?InstanceBoundaryIntegration.POLICY:"none");
            String albumId = activeAlbum;
            processing.submit(request, original -> detectPage(original,requested), (finished, result) ->
                    finishProcessing(albumId, finished.sourceId, owner -> owner.commitDetection(finished, result)));
            return;
        }
    }

    private interface ProcessingCommit { boolean commit(ScanSession owner) throws Exception; }

    private void finishProcessing(String albumId, String sourceId, ProcessingCommit commit) {
        boolean changed = false;
        try {
            if (albumId.equals(activeAlbum)) {
                changed = batch != null && commit.commit(batch);
            } else if (albumExists(albumStorage(), albumId)) {
                try (ScanSession owner = new ScanSession(albumRoot(albumId))) {
                    commit.commit(owner);
                }
            }
        } catch (Exception | LinkageError | OutOfMemoryError error) {
            if (!albumId.equals(activeAlbum)) {
                android.util.Log.w("PhotoScanState", "Background album processing paused", error);
                scheduleProcessing();
                return;
            }
            reloadSession();
            postProcessingError("Processing paused. Reopen the batch to retry. " + describe(error));
            return;
        }
        if (changed && (cameraVisible || !batch.editing || batch.current() == null || batch.current().id.equals(sourceId))) {
            try {
                Screen display = buildScreen();
                display.albumId = activeAlbum;
                display.libraryVisible = libraryVisible;
                display.albums = albumEntries;
                display.undoCount = undoCrops.size();
                main.post(() -> {
                    screen = display;
                    notifyScreen();
                });
            } catch (Exception | LinkageError | OutOfMemoryError error) {
                postProcessingError(describe(error));
            }
        }
        scheduleProcessing();
    }

    private void postProcessingError(String notice) {
        main.post(() -> {
            message = notice;
            notifyScreen();
        });
    }

    private static float center(float[] quad, int axis) {
        return (quad[axis] + quad[axis + 2] + quad[axis + 4] + quad[axis + 6]) / 4f;
    }

    private static void validatePhoto(File path) throws IOException {
        if (!path.isFile() || path.length() == 0) throw new IOException("The photo file is empty.");
        try {
            ImageEngine.preview(path, 160).recycle();
        } catch (Exception error) {
            throw new IOException("The photo could not be decoded.", error);
        }
    }

    private void validateImportedPhoto(File path) throws IOException {
        validatePhoto(path);
        // A picker may return a format ImageDecoder can preview but region export cannot crop.
        ImageEngine.render(path, new float[]{0, 0, 1, 0, 1, 1, 0, 1}, 0, 0f, 128).recycle();
    }

    static void commitOriginal(File captured, File original) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(captured, "rw")) {
            file.getFD().sync();
        }
        if (!captured.equals(original) && !captured.renameTo(original)) {
            throw new IOException("Could not store the private original.");
        }
        syncDirectory(original.getParentFile());
        if (!captured.equals(original)) syncDirectory(captured.getParentFile());
    }

    static void syncDirectory(File directory) throws IOException {
        try {
            FileDescriptor descriptor = Os.open(directory.getAbsolutePath(),
                    OsConstants.O_RDONLY, 0);
            try {
                Os.fsync(descriptor);
            } finally {
                Os.close(descriptor);
            }
        } catch (ErrnoException error) {
            throw new IOException("Could not commit the private photo directory.", error);
        }
    }

    private void reloadSession() {
        if (batch != null) batch.close();
        batch = null;
        try {
            if (albumExists(albumStorage(), activeAlbum)) {
                batch = new ScanSession(albumRoot(activeAlbum));
                PageRemoval.recover(batch);
            }
        } catch (Exception recovery) {
            if (batch != null) batch.close();
            batch = null;
        }
        if (batch == null) {
            activeAlbum = "";
            libraryVisible = true;
        }
    }

    private interface Job { String execute() throws Exception; }

    // Count queued external results so setup completion cannot briefly enable a competing action.
    private int jobs;

    private void run(String status, Job work) {
        if (!busy) enqueue(status, work);
    }

    private void enqueue(String status, Job work) {
        enqueue(status, work, null);
    }

    private void enqueue(String status, Job work, Runnable completion) {
        jobs++;
        busy = true;
        progress = status;
        notifyScreen();
        worker.execute(() -> {
            String result;
            try {
                result = work.execute();
            } catch (Exception | OutOfMemoryError | LinkageError error) {
                result = describe(error);
                reloadSession();
            }
            Screen refreshed = null;
            try {
                refreshed = buildScreen();
            } catch (Exception | OutOfMemoryError | LinkageError error) {
                if (result.isEmpty()) result = describe(error);
                Bitmap visible = batch != null && batch.current() != null && batch.current().id.equals(sourcePreviewId)
                        ? sourcePreview : null;
                refreshed = new Screen(batch, visible, null);
            }
            if (refreshed != null) {
                refreshed.albumId = activeAlbum;
                refreshed.libraryVisible = libraryVisible;
                refreshed.albums = albumEntries;
                refreshed.undoCount = undoCrops.size();
            }
            Screen display = refreshed;
            String notice = result;
            main.post(() -> {
                screen = display;
                ready = display != null;
                message = notice;
                busy = --jobs > 0;
                notifyScreen();
                if (completion != null) completion.run();
            });
            scheduleProcessing();
        });
    }

    private void report(String status) {
        main.post(() -> {
            progress = status;
            notifyScreen();
        });
    }

    private void notifyScreen() {
        if (activity != null) activity.render(screen, ready, busy, busy ? progress : message);
        if (cameraActivity != null) cameraActivity.renderSession(screen, ready, busy, busy ? progress : message);
    }

    private static String describe(Throwable error) {
        if (error instanceof OutOfMemoryError) return "The phone ran out of memory while processing this photo.";
        if (error instanceof LinkageError) return "Automatic crop detection is unavailable. You can add crops manually.";
        String detail = error.getLocalizedMessage();
        return detail == null || detail.trim().isEmpty() ? "Could not complete this step." : detail;
    }

    private static boolean deleteContents(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return false;
        boolean success = true;
        for (File file : files) success &= !file.exists() || file.delete();
        return success;
    }

    private static void deleteFile(File file) {
        if (file.exists()) file.delete();
    }
}

