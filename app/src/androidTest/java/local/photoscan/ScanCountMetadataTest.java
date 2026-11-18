package local.photoscan;

import static org.junit.Assert.*;

import android.graphics.Bitmap;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class ScanCountMetadataTest {
    private static final float[] FRAME = {0, 0, 1, 0, 1, 1, 0, 1};

    @Test
    public void earlierCompletionKeepsItsOwnScanNumberAndZeroDiffersFromFailure() throws Exception {
        try (ScanSession batch = new ScanSession(testRoot())) {
            var older = capture(batch, 3);
            var newer = capture(batch, 2);
            var request = new PageProcessingQueue.Request(older, batch.original(older));
            request.startedElapsed = request.queuedElapsed + 10;
            request.finishedElapsed = request.startedElapsed + 20;
            assertTrue(batch.commitDetection(request, new PageProcessingQueue.Detection(List.of(), false)));
            var screen = new ScanController.Screen(batch, null, null, false);
            assertEquals(newer.id, screen.latestScan.sourceId);
            assertEquals(2, screen.latestScan.pageNumber);
            assertEquals("pending", screen.latestScan.status);
            assertNull(screen.latestScan.detectedCount);
            assertEquals(older.id, screen.latestCompleted.sourceId);
            assertEquals(1, screen.latestCompleted.pageNumber);
            assertEquals("success", screen.latestCompleted.status);
            assertEquals(Integer.valueOf(0), screen.latestCompleted.detectedCount);
            assertTrue(screen.latestCompleted.mismatch);
            assertEquals(1, screen.queuedCount);
            assertEquals(10, older.detectionMetadata.getLong("queue_ms"));
            assertEquals(20, older.detectionMetadata.getLong("detect_ms"));

            assertTrue(batch.commitDetection(new PageProcessingQueue.Request(newer, batch.original(newer)), null));
            screen = new ScanController.Screen(batch, null, null, false);
            assertEquals(newer.id, screen.latestCompleted.sourceId);
            assertEquals("failure", screen.latestCompleted.status);
            assertNull(screen.latestCompleted.detectedCount);
            assertFalse(screen.latestCompleted.mismatch);
            assertEquals(0, screen.queuedCount);
        }
    }

    @Test
    public void retakeAndRestartKeepTheNewSnapshotAndDropStaleDetection() throws Exception {
        File root = testRoot();
        PageProcessingQueue.Request stale;
        String replacement;
        try (ScanSession batch = new ScanSession(root)) {
            var older = capture(batch, 3);
            stale = new PageProcessingQueue.Request(older, batch.original(older));
            replacement = reserve(batch, 4);
            batch.retakeSource = older.id;
            batch.expectedCount = 6;
            batch.saveChanges();
        }
        try (ScanSession batch = new ScanSession(root)) {
            ScanController.commitPendingCapture(batch);
            var newer = batch.sources.get(0);
            assertEquals(replacement, newer.id);
            assertFalse(batch.commitDetection(stale, new PageProcessingQueue.Detection(List.of(FRAME), false)));
            assertNull(batch.pendingCapture);
            assertEquals(6, batch.expectedCount);
            assertEquals(4, newer.captureMetadata.getInt("expected_count"));
            assertEquals(2, newer.captureMetadata.getJSONObject("exposure").getInt("applied_index"));
            assertTrue(batch.commitDetection(new PageProcessingQueue.Request(newer, batch.original(newer)),
                    new PageProcessingQueue.Detection(List.of(FRAME), false)));
            newer.crops.clear();
            batch.saveManualEdit(newer);
        }
        try (ScanSession batch = new ScanSession(root)) {
            assertTrue(batch.sources.get(0).crops.isEmpty());
            assertEquals(Integer.valueOf(1), new ScanController.Screen(batch, null, null).latestCompleted.detectedCount);
        }
    }

    @Test
    public void redetectionKeepsCaptureLineageButStartsWithNoDetectorResult() throws Exception {
        File root = testRoot();
        File destination = new File(root, ScanSession.newId());
        try (ScanSession batch = new ScanSession(root)) {
            var original = capture(batch, 5);
            batch.commitDetection(new PageProcessingQueue.Request(original, batch.original(original)),
                    new PageProcessingQueue.Detection(List.of(FRAME), false));
            AlbumRedetection.create(batch, destination, ignored -> {});
            try (ScanSession copy = new ScanSession(destination)) {
                var queued = copy.sources.get(0);
                assertNotEquals(original.id, queued.id);
                assertEquals("redetect", queued.captureMetadata.getString("origin"));
                assertEquals(original.id, queued.captureMetadata.getString("parent_source_id"));
                assertEquals(5, queued.captureMetadata.getInt("expected_count"));
                assertEquals("camera", queued.captureMetadata.getJSONObject("original_capture").getString("origin"));
                assertTrue(queued.pending());
                assertNull(queued.detectionMetadata);
                assertNull(new ScanController.Screen(copy, null, null).latestScan);
            }
        }
    }

    private static ScanSession.Source capture(ScanSession batch, int expected) throws Exception {
        reserve(batch, expected);
        ScanController.commitPendingCapture(batch);
        return batch.sources.get(batch.sources.size() - 1);
    }

    private static String reserve(ScanSession batch, int expected) throws Exception {
        String id = ScanSession.newId();
        batch.pendingCamera = id;
        batch.pendingCapture = ScanMetadata.camera(new ScanController.CaptureOptions(
                expected, 0.7, 2, 2, 2.0 / 3, 1.0 / 3), ++batch.captureOrder);
        Bitmap bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888);
        try (var output = new FileOutputStream(batch.incoming(id))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output));
        } finally {
            bitmap.recycle();
        }
        batch.saveChanges();
        return id;
    }

    private static File testRoot() {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        return new File(context.getCacheDir(), "scan-count-" + ScanSession.newId());
    }
}
