package local.photoscan;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;

/** Builds a fresh detection queue from an album's retained source files. */
final class AlbumRedetection {
    private static final String STAGING_PREFIX = ".redetect-";

    private AlbumRedetection() {}

    static void create(ScanSession source, File destination) throws Exception {
        create(source, destination, ignored -> {});
    }

    static void create(ScanSession source, File destination, java.util.function.IntConsumer copied) throws Exception {
        var originals = source.sources.stream().filter(AlbumRedetection::isEligible)
                .collect(java.util.stream.Collectors.toList());
        if (originals.isEmpty()) throw new IOException("This album has no original scans to re-detect.");
        if (source.pendingDelete || !source.pendingCamera.isEmpty() || !source.imports.isEmpty()) {
            throw new IOException("Finish the current album work before re-detecting.");
        }
        File parent = destination.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("Could not open album storage.");
        }
        if (!isAlbumId(destination.getName())) throw new IOException("Invalid destination album ID.");
        if (destination.exists()) throw new IOException("The re-detection album already exists.");

        File staging = new File(parent, STAGING_PREFIX + destination.getName());
        if (!staging.mkdir()) throw new IOException("Could not prepare the re-detection album.");
        try {
            try (ScanSession copy = new ScanSession(staging)) {
                copy.album = source.album + " (test " + BuildConfig.VERSION_NAME + ")";
                copy.albumDate = source.albumDate;
                copy.expectedCount = source.expectedCount;
                copy.review = true;
                for (ScanSession.Source original : originals) {
                    File input = source.original(original);
                    if (!input.isFile() || input.length() == 0) {
                        throw new IOException("A retained scan is missing or empty.");
                    }
                    if (staging.getUsableSpace() < input.length()) {
                        throw new IOException("Not enough storage to copy the retained scans.");
                    }
                    ScanSession.Source queued = new ScanSession.Source(ScanSession.newId());
                    File output = copy.original(queued);
                    Files.copy(input.toPath(), output.toPath());
                    ScanController.commitOriginal(output, output);
                    queued.captureMetadata = ScanMetadata.derived("redetect", original);
                    copy.sources.add(queued);
                    copied.accept(copy.sources.size());
                }
                copy.save();
            }
            ScanController.syncDirectory(staging);
            if (!staging.renameTo(destination)) {
                throw new IOException("Could not finish creating the re-detection album.");
            }
            try {
                ScanController.syncDirectory(parent);
            } catch (IOException failure) {
                throw new IOException("The copy is complete. Open it from Albums to continue re-detection.", failure);
            }
        } finally {
            if (staging.exists() && !removeTree(staging)) {
                Log.w("PhotoScanState", "Could not clean up unfinished re-detection staging.");
            }
        }
    }

    static boolean isEligible(ScanSession.Source source) {
        return source.retainedFromSourceId.isEmpty();
    }

    static boolean isStaging(File directory) {
        String name = directory.getName();
        if (!name.startsWith(STAGING_PREFIX)) return false;
        return isAlbumId(name.substring(STAGING_PREFIX.length()));
    }

    private static boolean isAlbumId(String id) {
        try {
            return UUID.fromString(id).toString().equals(id);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    static void discardInterrupted(File parent) {
        File[] staging = parent.listFiles(AlbumRedetection::isStaging);
        if (staging == null) return;
        for (File directory : staging) {
            if (!removeTree(directory)) Log.w("PhotoScanState", "Could not remove unfinished re-detection staging.");
        }
    }

    private static boolean removeTree(File file) {
        // Never traverse a symlink, even inside a stale app-private staging directory.
        File[] children = Files.isSymbolicLink(file.toPath()) ? null : file.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!removeTree(child)) return false;
            }
        }
        return file.delete();
    }
}
