package local.photoscan;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class PageRemovalTest {
    private static final float[] INSET = {.10f, .12f, .88f, .12f, .88f, .92f, .10f, .92f};
    private static final float[] FULL = {0, 0, 1, 0, 1, 1, 0, 1};

    @Test public void keepCropsBakesPixelsAndRetainsMetadataAndPublishedExport() throws Exception {
        Context context = context();
        ImageEngine.initialize(context);
        var resolver = context.getContentResolver();
        File root = fixture("keep");
        Uri exported = null;
        try (ScanSession album = new ScanSession(root)) {
            album.album = "Retain-" + ScanSession.newId();
            album.albumDate = "1976-07-19";
            ScanSession.Source page = addSource(album, true);
            ScanSession.Source unaffected = addSource(album, true);
            ScanSession.Crop crop = addCrop(page);
            crop.turns = 1;
            crop.caption = "Caption";
            crop.dateNote = "summer 1979";
            crop.reviewReason = CropReviewPolicy.OVERLAP;
            album.save();
            File original = album.original(page);
            Bitmap expected = ImageEngine.render(original, crop.corners, crop.turns, 0f, 0);
            try {
                BatchExport exporter = new BatchExport(resolver, album);
                assertEquals(BatchExport.Result.SAVED, exporter.export(page, crop, 1, 1));
                exported = exporter.publishedUri(page, crop, 1, 1);
                assertNotNull(exported);
                byte[] published = uriBytes(context, exported);
                byte[] sibling = Files.readAllBytes(album.original(unaffected).toPath());

                assertTrue(PageRemoval.remove(album, 0, true, resolver));
                assertFalse(original.exists());
                assertNull(album.pageRemoval);
                try (ScanSession reopened = new ScanSession(root)) {
                    assertEquals(2, reopened.sources.size());
                    assertTrue(reopened.review);
                    assertTrue(reopened.editing);
                    assertTrue(reopened.originalsFirst);
                    assertEquals(unaffected.id, reopened.current().id);
                    ScanSession.Source retained = reopened.sources.get(0);
                    assertNotEquals(page.id, retained.id);
                    assertEquals(page.id, retained.retainedFromSourceId);
                    assertEquals(unaffected.id, reopened.sources.get(1).id);
                    assertArrayEquals(sibling, Files.readAllBytes(reopened.original(reopened.sources.get(1)).toPath()));
                    assertEquals(1, retained.crops.size());
                    ScanSession.Crop kept = retained.crops.get(0);
                    assertEquals(crop.id, kept.id);
                    assertArrayEquals(FULL, kept.corners, 0);
                    assertEquals(0, kept.turns);
                    assertEquals(crop.caption, kept.caption);
                    assertEquals(crop.dateNote, kept.dateNote);
                    assertEquals(crop.reviewReason, kept.reviewReason);
                    assertFalse(kept.orientationPending);
                    Bitmap baked = BitmapFactory.decodeFile(reopened.original(retained).getAbsolutePath());
                    assertNotNull(baked);
                    try {
                        assertEquals(expected.getWidth(), baked.getWidth());
                        assertEquals(expected.getHeight(), baked.getHeight());
                        assertTrue("JPEG encoding should preserve the rotated crop content",
                                meanRgbDifference(pixels(expected), pixels(baked)) < 3);
                    } finally {
                        baked.recycle();
                    }
                    BatchExport again = new BatchExport(resolver, reopened);
                    assertEquals(exported, again.publishedUri(retained, kept, 1, 1));
                    assertEquals(BatchExport.Result.ALREADY_SAVED, again.export(retained, kept, 1, 1));
                    assertArrayEquals(published, uriBytes(context, exported));
                    assertTrue(reopened.receipts.stream().anyMatch(receipt -> receipt.signature.startsWith(page.id + "/")));
                }
            } finally {
                expected.recycle();
            }
        } finally {
            if (exported != null) resolver.delete(exported, null, null);
            deleteFixture(root);
        }
    }

    @Test public void deletionChoosesNextGenuineScanThenPreviousAndSkipsRetainedPhotos() throws Exception {
        File root = fixture("original-navigation");
        try (ScanSession album = new ScanSession(root)) {
            var previous = addSource(album, true);
            var removed = addSource(album, true);
            var retainedBetween = addSource(album, true);
            retainedBetween.retainedFromSourceId = ScanSession.newId();
            var next = addSource(album, true);
            var retainedLast = addSource(album, true);
            retainedLast.retainedFromSourceId = ScanSession.newId();
            album.review = true;
            album.editing = true;
            album.originalsFirst = true;
            album.page = 1;
            album.save();
            var resolver = context().getContentResolver();

            assertTrue(PageRemoval.remove(album, album.page, false, resolver));
            assertEquals(next.id, album.current().id);
            assertEquals(List.of(previous.id, retainedBetween.id, next.id, retainedLast.id),
                    album.sources.stream().map(page -> page.id).collect(java.util.stream.Collectors.toList()));

            assertTrue(PageRemoval.remove(album, album.page, false, resolver));
            assertEquals(previous.id, album.current().id);
            try (ScanSession reopened = new ScanSession(root)) {
                assertEquals(previous.id, reopened.current().id);
                assertTrue(reopened.review);
                assertTrue(reopened.editing);
                assertTrue(reopened.originalsFirst);
                assertTrue(reopened.original(retainedBetween).isFile());
                assertTrue(reopened.original(retainedLast).isFile());
            }

            assertTrue(PageRemoval.remove(album, album.page, false, resolver));
            assertEquals(List.of(retainedBetween.id, retainedLast.id),
                    album.sources.stream().map(page -> page.id).collect(java.util.stream.Collectors.toList()));
            assertEmptyOriginalReview(root, 2);
        } finally {
            deleteFixture(root);
        }
    }

    @Test public void deletingOnlyScanRestoresEmptyOriginalReviewAfterReopen() throws Exception {
        File root = fixture("empty-original-navigation");
        try {
            try (ScanSession album = new ScanSession(root)) {
                addSource(album, true);
                album.review = true;
                album.editing = true;
                album.originalsFirst = true;
                album.save();
                assertTrue(PageRemoval.remove(album, 0, false, context().getContentResolver()));
            }
            assertEmptyOriginalReview(root, 0);
        } finally {
            deleteFixture(root);
        }
    }

    private static void assertEmptyOriginalReview(File root, int retainedCount) throws Exception {
        try (ScanSession reopened = new ScanSession(root)) {
            assertEquals(retainedCount, reopened.sources.size());
            assertTrue(reopened.review);
            assertTrue(reopened.editing);
            assertTrue(reopened.originalsFirst);
            assertEquals(-1, reopened.page);
            assertNull(reopened.current());
            assertNull(reopened.selected());
            assertNull(reopened.pageRemoval);
        }
    }

    @Test public void failedRenderKeepsOriginalPageAndDatabase() throws Exception {
        Context context = context();
        File root = fixture("render-failure");
        try (ScanSession album = new ScanSession(root)) {
            ScanSession.Source page = addSource(album, false);
            addCrop(page);
            album.save();
            File original = album.original(page);
            byte[] raw = Files.readAllBytes(original.toPath());
            String database = storedState(root);
            assertThrows(IOException.class, () -> PageRemoval.remove(album, 0, true, context.getContentResolver()));
            assertArrayEquals(raw, Files.readAllBytes(original.toPath()));
            assertEquals(database, storedState(root));
            try (ScanSession reopened = new ScanSession(root)) {
                assertEquals(page.id, reopened.sources.get(0).id);
                assertEquals(1, reopened.sources.get(0).crops.size());
                assertNull(reopened.pageRemoval);
                assertEquals(1, reopened.originals.list().length);
            }
        } finally {
            deleteFixture(root);
        }
    }

    @Test public void interruptedPrecommitJournalKeepsOriginalAndRemovesTemporaryReplacements() throws Exception {
        File root = fixture("precommit");
        try {
            String oldId;
            String keptId = ScanSession.newId();
            byte[] raw;
            try (ScanSession album = new ScanSession(root)) {
                var original = addSource(album, true);
                oldId = original.id;
                raw = Files.readAllBytes(album.original(original).toPath());
                addCrop(original);
                album.pageRemoval = new ScanSession.PageRemoval(oldId, List.of(keptId));
                album.save();
                Files.write(album.original(new ScanSession.Source(keptId)).toPath(), new byte[]{1, 2, 3});
                Files.write(album.incoming(keptId).toPath(), new byte[]{4, 5, 6});
            }
            try (ScanSession restarted = new ScanSession(root)) {
                assertTrue(PageRemoval.recover(restarted));
                assertTrue(PageRemoval.recover(restarted));
                assertEquals(1, restarted.sources.size());
                assertEquals(oldId, restarted.sources.get(0).id);
                assertEquals(1, restarted.sources.get(0).crops.size());
                assertArrayEquals(raw, Files.readAllBytes(restarted.original(restarted.sources.get(0)).toPath()));
                assertFalse(restarted.original(new ScanSession.Source(keptId)).exists());
                assertFalse(restarted.incoming(keptId).exists());
            }
        } finally {
            deleteFixture(root);
        }
    }

    @Test public void committedDeletionJournalResumesWithoutRemovingRetainedSourcesOrReceipts() throws Exception {
        File root = fixture("journal");
        try {
            String oldId;
            String keptId;
            String ticket = ScanSession.newId();
            try (ScanSession album = new ScanSession(root)) {
                var original = addSource(album, true);
                var retained = addSource(album, true);
                oldId = original.id;
                keptId = retained.id;
                retained.retainedFromSourceId = oldId;
                album.sources.remove(original);
                album.receipts.add(new ScanSession.Receipt("historical", ticket, "previous-export.jpg"));
                album.pageRemoval = new ScanSession.PageRemoval(oldId, List.of(keptId));
                album.save();
            }
            try (ScanSession restarted = new ScanSession(root)) {
                File oldFile = restarted.original(new ScanSession.Source(oldId));
                assertTrue(oldFile.isFile());
                assertTrue(PageRemoval.recover(restarted));
                assertTrue(PageRemoval.recover(restarted));
                assertFalse(oldFile.exists());
                assertNull(restarted.pageRemoval);
                assertTrue(restarted.original(restarted.sources.get(0)).isFile());
                assertEquals(keptId, restarted.sources.get(0).id);
                assertEquals(ticket, restarted.receipts.get(0).ticket);
            }
            try (ScanSession reopened = new ScanSession(root)) {
                assertNull(reopened.pageRemoval);
                assertEquals(keptId, reopened.sources.get(0).id);
            }
        } finally {
            deleteFixture(root);
        }
    }

    private static ScanSession.Crop addCrop(ScanSession.Source page) {
        ScanSession.Crop crop = new ScanSession.Crop(ScanSession.newId(), INSET.clone());
        page.crops.add(crop);
        return crop;
    }

    private static ScanSession.Source addSource(ScanSession album, boolean jpeg) throws IOException {
        ScanSession.Source page = new ScanSession.Source(ScanSession.newId());
        page.detected = true;
        album.sources.add(page);
        if (!jpeg) {
            Files.write(album.original(page).toPath(), new byte[]{1, 2, 3});
            return page;
        }
        Bitmap image = Bitmap.createBitmap(80, 56, Bitmap.Config.ARGB_8888);
        try (FileOutputStream output = new FileOutputStream(album.original(page))) {
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    image.setPixel(x, y, 0xff000000 | (x * 3 << 16) | (y * 4 << 8) | (x + y));
                }
            }
            assertTrue(image.compress(Bitmap.CompressFormat.JPEG, 92, output));
        } finally {
            image.recycle();
        }
        return page;
    }

    private static int[] pixels(Bitmap image) {
        int width = image.getWidth(), height = image.getHeight();
        int[] pixels = new int[width * height];
        image.getPixels(pixels, 0, width, 0, 0, width, height);
        return pixels;
    }

    private static double meanRgbDifference(int[] first, int[] second) {
        long sum = 0;
        for (int i = 0; i < first.length; i++) {
            for (int shift : new int[]{0, 8, 16}) {
                sum += Math.abs(((first[i] >> shift) & 255) - ((second[i] >> shift) & 255));
            }
        }
        return (double) sum / (3 * first.length);
    }

    private static String storedState(File root) throws Exception {
        try (BatchStore store = new BatchStore(new File(root, "batch.db"))) {
            return store.read().toString();
        }
    }

    private static byte[] uriBytes(Context context, Uri uri) throws IOException {
        try (var input = context.getContentResolver().openInputStream(uri)) {
            assertNotNull(input);
            return input.readAllBytes();
        }
    }

    private static Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private static File fixture(String kind) throws IOException {
        return Files.createTempDirectory(context().getCacheDir().toPath(), "page-remove-" + kind + "-").toFile();
    }

    private static void deleteFixture(File root) throws IOException {
        try (var files = Files.walk(root.toPath())) {
            for (var path : files.sorted(java.util.Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) {
                Files.deleteIfExists(path);
            }
        }
    }
}
