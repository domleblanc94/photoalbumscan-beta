package local.photoscan;

import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PublicBetaTest {
    @Test public void bundledNoticesAreReadableWithoutNetwork() throws Exception {
        String notices = OpenSourceLicenses.read(InstrumentationRegistry.getInstrumentation().getTargetContext());
        assertTrue(notices.contains("PhotoAlbumScan"));
        assertTrue(notices.contains("OpenCV"));
    }

    @Test public void removedFaceRotationAbstainsWithoutReadingOrAllocatingAnImage() {
        assertEquals(-1, ImageEngine.suggestRotation(null));
        assertEquals(-1, SourceRotation.suggest((java.io.File) null, null));
    }
    @Test public void manualQuarterTurnPreservesFullSyntheticPicture() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var source = java.io.File.createTempFile("synthetic-rotation-", ".png", context.getCacheDir());
        var image = android.graphics.Bitmap.createBitmap(240, 120, android.graphics.Bitmap.Config.ARGB_8888);
        image.eraseColor(android.graphics.Color.CYAN);
        try {
            try (var out = new java.io.FileOutputStream(source)) {
                assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out));
            }
            float[] full = {0, 0, 1, 0, 1, 1, 0, 1};
            var straight = ImageEngine.render(source, full, 0, 0, 0);
            var rotated = ImageEngine.render(source, full, 1, 0, 0);
            try {
                assertEquals(straight.getWidth(), rotated.getHeight());
                assertEquals(straight.getHeight(), rotated.getWidth());
                assertEquals(android.graphics.Color.CYAN, rotated.getPixel(rotated.getWidth()/2, rotated.getHeight()/2));
            } finally { straight.recycle(); rotated.recycle(); }
        } finally { image.recycle(); source.delete(); }
    }

    @Test public void selectedModelsProcessSyntheticPageWithoutPrivateFixtures() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var source = java.io.File.createTempFile("synthetic-detection-", ".png", context.getCacheDir());
        var image = android.graphics.Bitmap.createBitmap(640, 480, android.graphics.Bitmap.Config.ARGB_8888);
        image.eraseColor(android.graphics.Color.DKGRAY);
        var canvas = new android.graphics.Canvas(image);
        var paint = new android.graphics.Paint();
        paint.setColor(android.graphics.Color.WHITE);
        canvas.drawRect(90, 70, 550, 410, paint);
        paint.setColor(android.graphics.Color.BLUE);
        canvas.drawRect(110, 90, 530, 390, paint);
        try {
            try (var out = new java.io.FileOutputStream(source)) {
                assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out));
            }
            ImageEngine.initialize(context);
            try (var detector = new PictureContentDetector(context)) {
                assertNotNull(detector.detect(source));
            }
        } finally { image.recycle(); source.delete(); }
    }    @Test public void applicationHasNoNetworkPermissionOrAutomaticTelemetryProvider() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var info = context.getPackageManager().getPackageInfo(context.getPackageName(),
                android.content.pm.PackageManager.GET_PERMISSIONS | android.content.pm.PackageManager.GET_PROVIDERS);
        if (info.requestedPermissions != null) for (String permission : info.requestedPermissions) {
            assertNotEquals("android.permission.INTERNET", permission);
            assertNotEquals("android.permission.ACCESS_NETWORK_STATE", permission);
        }
        if (info.providers != null) for (var provider : info.providers) {
            assertNotEquals("ai.onnxruntime.TelemetryInitializer", provider.name);
        }
        assertNotNull(OfflineOrt.environment());
    }}