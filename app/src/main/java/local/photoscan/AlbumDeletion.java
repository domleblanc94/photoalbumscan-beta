package local.photoscan;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;

/** Owns album storage paths and retryable deletion of private album files. */
final class AlbumDeletion {
    static final String FIRST_ALBUM = "original";
    private static final String LEGACY_MARKER = ".original-album-deleted";
    private static final String DELETED_PREFIX = ".deleted-";
    private static final String[] LEGACY_FILES = {"batch.db", "batch.db-journal", "batch.db-wal", "batch.db-shm",
            "batch.json", "batch.json.bak", "batch.json.new", "batch.migrated.json"};

    private final File files;
    private final File albums;

    AlbumDeletion(File files) throws IOException {
        this.files = files.getCanonicalFile();
        albums = child(this.files, "albums");
    }

    File root(String id) throws IOException {
        if (FIRST_ALBUM.equals(id)) return files;
        if (!uuid(id)) throw new IOException("Album could not be found.");
        return child(albums, id);
    }

    boolean exists(String id) throws IOException {
        File root = root(id);
        if (FIRST_ALBUM.equals(id) && present(marker())) return false;
        return regular(new File(root, "batch.db"))
                || FIRST_ALBUM.equals(id) && (regular(new File(root, "batch.json"))
                || regular(new File(root, "batch.json.bak")));
    }

    void retire(String id) throws IOException {
        if (!exists(id)) throw new IOException("Album could not be found.");
        if (FIRST_ALBUM.equals(id)) {
            // The marker itself contains no data. Its durable presence makes legacy files unreachable.
            File marker = marker();
            if (!marker.createNewFile()) throw new IOException("Album has already been deleted.");
            try (FileOutputStream output = new FileOutputStream(marker)) {
                output.getFD().sync();
            }
            ScanController.syncDirectory(files);
        } else {
            File tombstone = child(albums, DELETED_PREFIX + id);
            if (present(tombstone) || !root(id).renameTo(tombstone)) {
                throw new IOException("Could not remove the album.");
            }
            ScanController.syncDirectory(albums);
        }
    }

    boolean cleanup() {
        boolean complete = true;
        try {
            if (present(marker())) {
                ScanController.syncDirectory(files);
                deleteTree(new File(files, "originals").toPath());
                deleteTree(new File(files, "incoming").toPath());
                for (String name : LEGACY_FILES) Files.deleteIfExists(new File(files, name).toPath());
                ScanController.syncDirectory(files);
            }
        } catch (IOException failure) {
            complete = false;
        }
        File[] retired = albums.listFiles(file -> file.getName().startsWith(DELETED_PREFIX)
                && uuid(file.getName().substring(DELETED_PREFIX.length())));
        if (retired != null) for (File file : retired) {
            try {
                ScanController.syncDirectory(albums);
                deleteTree(file.toPath());
                ScanController.syncDirectory(albums);
            } catch (IOException failure) {
                complete = false;
            }
        }
        return complete;
    }

    private File marker() { return new File(files, LEGACY_MARKER); }

    private static boolean present(File file) {
        return Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean regular(File file) {
        return Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean uuid(String id) {
        if (id == null) return false;
        try {
            return UUID.fromString(id).toString().equals(id);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static File child(File parent, String name) throws IOException {
        File path = new File(parent, name);
        if (!path.equals(path.getCanonicalFile())) throw new IOException("Unsafe album storage path.");
        return path;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult postVisitDirectory(Path path, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
