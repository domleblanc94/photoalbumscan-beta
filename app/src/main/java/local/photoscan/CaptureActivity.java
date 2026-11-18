package local.photoscan;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Size;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.ResolutionInfo;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class CaptureActivity extends ComponentActivity implements SensorEventListener {
    static final String EXTRA_RETAKE = "retake";
    static final String EXTRA_REVIEW = "review";
    static final String EXTRA_REVIEW_SOURCE_ID = "reviewSourceId";
    static final String EXTRA_NOTICE = "notice";
    static final String EXTRA_IMPORT = "import";
    private static final int CAMERA_PERMISSION = 41;
    private static final int BACKGROUND = 0xFF142A29;
    private static final int TEXT = 0xFFF3FBF8;
    private static final int MUTED = 0xFFB7D4CE;
    private static final long ANALYSIS_INTERVAL_MS = 200;
    private static final long SAVED_NOTICE_MS = 1200;
    private static final long LOW_STORAGE_BYTES = 1024L * 1024 * 1024;
    private static final long STORAGE_NOTICE_MS = 8000;
    private static final long EXPOSURE_TIMEOUT_MS = 2500;
    private static final String CAMERA_PREFERENCES = "camera";
    private static final String GLARE_PREFERENCE = "glare_hints";


    private final ExecutorService analysisWorker = Executors.newSingleThreadExecutor();
    private final ThreadPoolExecutor thumbnailWorker = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
    private Future<?> thumbnailTask;
    private int thumbnailGeneration;
    private boolean savedNotice;
    private volatile boolean analysisActive;
    private volatile int overlayGeneration;
    private volatile boolean glareEnabled;
    private volatile int glareSettingGeneration;
    private long nextAnalysis;
    private long nextGlareAnalysis;
    private boolean liveEngineReady;
    private GlareAnalyzer glareAnalyzer;
    private final GlareHint glareHint = new GlareHint();

    private ScanController controller;
    private Executor mainExecutor;
    private ScanController.Screen session;
    private PreviewView viewfinder;
    private CameraGuideView guides;
    private LinearLayout cameraLayout, header, heading, scanResults, footer, hints, buttons, secondaryButtons, rail, landscapePanel;
    private FrameLayout previewFrame;
    private ScrollView railScroll;
    private Switch glareToggle;
    private boolean landscapeControls;
    private TextView cameraTitle, count, latestResult, earlierResult, exposureStatus, levelHint, status, storageWarning;
    private ImageView lastSaved;
    private Button shutter, done, importPhotos, options, checkScan;
    private final Runnable clearSavedNotice = () -> {
        savedNotice = false;
        updateControls();
    };

    private ProcessCameraProvider provider;
    private Camera camera;
    private Preview previewUseCase;
    private ImageCapture imageCapture;
    private ImageAnalysis imageAnalysis;
    private boolean loadingCamera;
    private boolean streaming;
    private boolean resumed;
    private boolean retake;
    private boolean requestedPermission;
    private boolean sessionReady;
    private boolean sessionBusy;
    private boolean taking;
    private boolean saving;
    private boolean cameraFailed;
    private boolean checkedStorage;
    private int heldCaptureKeys;
    private boolean liveAvailable = true;
    private boolean liveOutlines;
    private int requestedExposureEv;
    private CameraExposureTestMode.Setting confirmedExposure;
    private boolean exposureBlocked;
    private final CameraExposureTestMode.RequestGate exposureRequests = new CameraExposureTestMode.RequestGate();
    private String exposureError = "";
    private String thumbnailSourceId = "";
    private CollectionExposureSet collectionSet;
    private boolean restoringCollectionExposure;
    private boolean collectionUnavailableShot;
    private File lastCollectionOriginal;
    private boolean softWarning;
    private int softFrames;
    private int sharpFrames;
    private Boolean exitAfterShot;
    private String sessionNotice = "";
    private String cameraNotice = "";
    private int rotation = Surface.ROTATION_0;

    private SensorManager sensorManager;
    private DisplayManager displayManager;
    private boolean gravitySensor;
    private boolean hasGravitySample;
    private boolean aligned;
    private final float[] gravity = new float[3];

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) {}
        @Override public void onDisplayRemoved(int id) {}
        @Override public void onDisplayChanged(int id) {
            Display display = viewfinder.getDisplay();
            if (display != null && id == display.getDisplayId()) updateRotation();
        }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        retake = getIntent().getBooleanExtra(EXTRA_RETAKE, false);
        glareEnabled = getSharedPreferences(CAMERA_PREFERENCES, MODE_PRIVATE)
                .getBoolean(GLARE_PREFERENCE, true);
        requestedPermission = saved != null && saved.getBoolean("requestedPermission");
        liveOutlines = saved != null && saved.getBoolean("liveOutlines");
        requestedExposureEv = saved == null || saved.getBoolean("collectionSession") ? 0 : saved.getInt("requestedExposureEv");
        mainExecutor = ContextCompat.getMainExecutor(this);
        sensorManager = getSystemService(SensorManager.class);
        displayManager = getSystemService(DisplayManager.class);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(false);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightNavigationBars(false);
        makeLayout();
        controller = ScanController.get(this);
        controller.attachCamera(this);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { requestExit(false); }
        });
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("requestedPermission", requestedPermission);
        state.putBoolean("liveOutlines", liveOutlines);
        state.putInt("requestedExposureEv", requestedExposureEv);
        state.putBoolean("collectionSession", isCollection());
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        checkStorageOnce();
        invalidateSuggestions();
        if (displayManager != null) displayManager.registerDisplayListener(displayListener, null);
        updateRotation();
        startLevel();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else if (!requestedPermission) {
            requestedPermission = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        }
        streaming = viewfinder.getPreviewStreamState().getValue() == PreviewView.StreamState.STREAMING;
        if (exposureBlocked && camera != null && collectionSet == null && !exposureRequests.pending()) applyExposure(0);
        updateControls();
    }

    @Override protected void onPause() {
        resumed = false;
        if (collectionSet != null) collectionSet.stop();
        heldCaptureKeys = 0;
        storageWarning.removeCallbacks(hideStorageWarning);
        hideStorageWarning.run();
        clearCaptureFeedback();
        invalidateSuggestions();
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (displayManager != null) displayManager.unregisterDisplayListener(displayListener);
        guides.showTilt(null);
        updateControls();
        super.onPause();
    }

    @Override protected void onDestroy() {
        controller.detachCamera(this);
        exposureRequests.clear();
        clearCaptureFeedback();
        thumbnailWorker.shutdownNow();
        analysisActive = false;
        if (imageAnalysis != null) imageAnalysis.clearAnalyzer();
        if (provider != null && previewUseCase != null && imageCapture != null) {
            if (imageAnalysis == null) provider.unbind(previewUseCase, imageCapture);
            else provider.unbind(previewUseCase, imageCapture, imageAnalysis);
        }
        analysisWorker.shutdown();
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (collectionSet != null) stopCollectionSet();
        layoutCamera(configuration);
        updateRotation();
        invalidateSuggestions();
        updateControls();
        viewfinder.post(this::updateRotation);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != CAMERA_PERMISSION) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) openCamera();
        else closeCamera(false, "Camera permission is off. You can still import photos.");
    }

    private void openCamera() {
        if (loadingCamera || provider != null || isFinishing() || cameraFailed) return;
        loadingCamera = true;
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            if (isFinishing() || isDestroyed()) return;
            loadingCamera = false;
            try {
                provider = future.get();
                bindCamera();
            } catch (Exception error) {
                cameraFailed = true;
                cameraNotice = "Camera unavailable. You can still import photos.";
            }
            updateControls();
        }, mainExecutor);
    }

    private void bindCamera() {
        updateRotation();
        Preview preview = new Preview.Builder().setTargetRotation(rotation).build();
        preview.setSurfaceProvider(viewfinder.getSurfaceProvider());
        ResolutionSelector captureResolution = new ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                .build();
        ImageCapture capture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setJpegQuality(95)
                .setResolutionSelector(captureResolution)
                .setTargetRotation(rotation)
                .build();
        ResolutionSelector analysisResolution = new ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(new ResolutionStrategy(new Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build();
        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setResolutionSelector(analysisResolution)
                .setTargetRotation(rotation)
                .build();
        analysis.setAnalyzer(analysisWorker, this::analyze);
        try {
            // The sensor-coordinate mapping aligns preview and analysis while leaving the JPEG uncropped.
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis);
            imageAnalysis = analysis;
        } catch (IllegalArgumentException combinationUnsupported) {
            analysis.clearAnalyzer();
            provider.unbind(preview, capture, analysis);
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture);
            liveAvailable = false;
        }
        previewUseCase = preview;
        imageCapture = capture;
        if (isCollection()) requestedExposureEv = 0;
        var exposure = camera.getCameraInfo().getExposureState();
        if (requestedExposureEv != 0 || exposure.isExposureCompensationSupported()
                && exposure.getExposureCompensationIndex() != 0) applyExposure(requestedExposureEv);
        else confirmedExposure = exposureSetting(0);
    }

    private void updateRotation() {
        Display display = viewfinder.getDisplay();
        if (display == null || display.getRotation() == rotation) return;
        rotation = display.getRotation();
        if (previewUseCase != null) previewUseCase.setTargetRotation(rotation);
        if (imageCapture != null) imageCapture.setTargetRotation(rotation);
        if (imageAnalysis != null) imageAnalysis.setTargetRotation(rotation);
        invalidateSuggestions();
        if (hasGravitySample) updateLevel();
    }

    private void analyze(ImageProxy frame) {
        Bitmap upright = null;
        int generation = overlayGeneration;
        try {
            long measuredAt = SystemClock.elapsedRealtime();
            if (!analysisActive || measuredAt < nextAnalysis) return;
            nextAnalysis = measuredAt + ANALYSIS_INTERVAL_MS;
            Matrix uprightToSensor;
            try {
                Rect crop = new Rect(frame.getCropRect());
                if (!crop.intersect(0, 0, frame.getWidth(), frame.getHeight())) return;
                int degrees = frame.getImageInfo().getRotationDegrees();
                Matrix bufferToUpright = CameraFrameGeometry.bufferToUpright(crop, degrees);
                Matrix uprightToBuffer = new Matrix();
                Matrix bufferToSensor = new Matrix();
                if (!bufferToUpright.invert(uprightToBuffer)
                        || !frame.getImageInfo().getSensorToBufferTransformMatrix().invert(bufferToSensor)) return;
                uprightToSensor = new Matrix();
                uprightToSensor.setConcat(bufferToSensor, uprightToBuffer);

                Bitmap buffer = frame.toBitmap();
                try {
                    boolean swapped = degrees % 180 != 0;
                    upright = Bitmap.createBitmap(swapped ? crop.height() : crop.width(),
                            swapped ? crop.width() : crop.height(), Bitmap.Config.ARGB_8888);
                    new Canvas(upright).drawBitmap(buffer, bufferToUpright, new Paint(Paint.FILTER_BITMAP_FLAG));
                } finally {
                    buffer.recycle();
                }
            } finally {
                ImageProxy consumed = frame;
                frame = null;
                consumed.close();
            }

            if (!liveEngineReady) {
                ImageEngine.initialize(getApplicationContext());
                liveEngineReady = true;
            }
            List<float[]> candidates = ImageEngine.detectLive(upright);
            float sharpness = measureSharpness(upright, candidates);
            int glareSetting = glareSettingGeneration;
            boolean sampleGlare = glareEnabled && measuredAt >= nextGlareAnalysis;
            if (sampleGlare) nextGlareAnalysis = measuredAt + GlareHint.SAMPLE_INTERVAL_MS;
            float[][] glare = sampleGlare ? measureGlare(upright, candidates) : null;
            List<float[]> sensorQuads = new ArrayList<>();
            for (float[] candidate : candidates) {
                if (sensorQuads.size() == ScanSession.MAX_CROPS) break;
                float[] pixels = candidate.clone();
                for (int i = 0; i < pixels.length; i += 2) {
                    pixels[i] *= upright.getWidth();
                    pixels[i + 1] *= upright.getHeight();
                }
                uprightToSensor.mapPoints(pixels);
                sensorQuads.add(pixels);
            }
            mainExecutor.execute(() -> publishSuggestions(sensorQuads, generation, sharpness,
                    sampleGlare, glareSetting, glare, measuredAt));
        } catch (Exception | LinkageError | OutOfMemoryError unavailable) {
            mainExecutor.execute(() -> {
                if (generation != overlayGeneration || !analysisActive || isDestroyed() || isFinishing()) return;
                liveAvailable = false;
                invalidateSuggestions();
                updateControls();
            });
        } finally {
            if (frame != null) frame.close();
            if (upright != null) upright.recycle();
        }
    }

    private static float measureSharpness(Bitmap frame, List<float[]> candidates) {
        try {
            return ImageEngine.sharpness(frame, candidates);
        } catch (RuntimeException unavailable) {
            return Float.NaN;
        }
    }

    private float[][] measureGlare(Bitmap frame, List<float[]> candidates) {
        if (candidates.isEmpty()) return null;
        float[][] spots = new float[Math.min(candidates.size(), ScanSession.MAX_CROPS)][];
        try {
            if (glareAnalyzer == null) glareAnalyzer = new GlareAnalyzer();
            for (int i = 0; i < spots.length; i++) {
                spots[i] = glareAnalyzer.findSpot(frame, candidates.get(i));
            }
        } catch (RuntimeException | LinkageError | OutOfMemoryError unavailable) {
            // Optional analysis cannot disable live outlines.
        }
        return spots;
    }

    private void publishSuggestions(List<float[]> sensorQuads, int generation, float sharpness,
                                    boolean sampleGlare, int glareSetting, float[][] glare,
                                    long measuredAt) {
        if (!analysisActive || generation != overlayGeneration || isDestroyed() || isFinishing()
                || SystemClock.elapsedRealtime() - measuredAt >= LiveOutlineMotion.MAX_FRAME_AGE_MS) return;
        Matrix sensorToPreview = viewfinder.getSensorToViewTransform();
        if (sensorToPreview == null) return;
        RectF bounds = previewBounds();
        List<float[]> projected = new ArrayList<>();
        int visibleOutlines = 0;
        for (int index = 0; index < sensorQuads.size(); index++) {
            float[] quad = sensorQuads.get(index);
            sensorToPreview.mapPoints(quad);
            boolean visible = true;
            for (int i = 0; i < quad.length; i += 2) {
                if (!bounds.contains(quad[i], quad[i + 1])) {
                    visible = false;
                    break;
                }
            }
            if (visible) {
                projected.add(quad);
                visibleOutlines |= 1 << index;
            }
        }
        boolean visible = guides.showSuggestions(projected, measuredAt);
        boolean priorWarning = softWarning;
        boolean priorGlare = glareHint.visible();
        updateSharpness(visible ? sharpness : Float.NaN);
        if (!visible) glareHint.clear();
        else if (sampleGlare && glareSetting == glareSettingGeneration) {
            glareHint.observeVisible(glareEnabled, glare, visibleOutlines, measuredAt);
        }
        if (softWarning != priorWarning || glareHint.visible() != priorGlare) updateControls();
    }

    private void updateSharpness(float score) {
        if (!Float.isFinite(score)) {
            softFrames = 0;
            sharpFrames = 0;
            softWarning = false;
        } else if (score < 8f) {
            sharpFrames = 0;
            if (softFrames < 3) softFrames++;
            if (softFrames == 3) softWarning = true;
        } else {
            softFrames = 0;
            if (score < 10.5f) sharpFrames = 0;
            else if (sharpFrames < 2) sharpFrames++;
            if (sharpFrames == 2) softWarning = false;
        }
    }

    private RectF previewBounds() {
        int width = viewfinder.getWidth();
        int height = viewfinder.getHeight();
        ResolutionInfo info = previewUseCase == null ? null : previewUseCase.getResolutionInfo();
        if (info == null) return new RectF(-1, -1, width + 1, height + 1);
        Rect crop = info.getCropRect();
        boolean swapped = info.getRotationDegrees() % 180 != 0;
        float previewWidth = swapped ? crop.height() : crop.width();
        float previewHeight = swapped ? crop.width() : crop.height();
        float scale = Math.min(width / previewWidth, height / previewHeight);
        float left = (width - scale * previewWidth) / 2;
        float top = (height - scale * previewHeight) / 2;
        return new RectF(left - 2, top - 2, width - left + 2, height - top + 2);
    }

    private void invalidateSuggestions() {
        overlayGeneration++;
        guides.clearSuggestions();
        clearSuggestionIndicators();
    }

    private void clearSuggestionIndicators() {
        boolean priorWarning = softWarning || glareHint.visible();
        updateSharpness(Float.NaN);
        glareHint.clear();
        if (priorWarning) updateControls();
    }

    void renderSession(ScanController.Screen screen, boolean ready, boolean busy, String notice) {
        session = screen;
        sessionReady = ready;
        sessionBusy = busy;
        sessionNotice = notice;
        positionCaptureStatus();
        if (isCollection() && collectionSet == null && camera != null && !exposureRequests.pending()
                && (requestedExposureEv != 0 || confirmedExposure != null && confirmedExposure.index() != 0)) {
            applyExposure(0);
        }
        updateControls();
    }

    private static int shutterKey(int keyCode) {
        return switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP -> 1;
            case KeyEvent.KEYCODE_VOLUME_DOWN -> 2;
            case KeyEvent.KEYCODE_CAMERA -> 4;
            default -> 0;
        };
    }

    private boolean cameraAcceptsKeys() {
        return resumed && hasWindowFocus() && !isFinishing() && !isDestroyed();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        int key = shutterKey(keyCode);
        if (key != 0 && cameraAcceptsKeys()) {
            boolean firstPress = (heldCaptureKeys & key) == 0 && event.getRepeatCount() == 0;
            heldCaptureKeys |= key;
            if (firstPress) takePhoto();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        int key = shutterKey(keyCode);
        if (key != 0 && ((heldCaptureKeys & key) != 0 || cameraAcceptsKeys())) {
            heldCaptureKeys &= ~key;
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) heldCaptureKeys = 0;
    }

    private final Runnable hideStorageWarning = () -> storageWarning.setVisibility(View.GONE);

    private void checkStorageOnce() {
        if (checkedStorage) return;
        checkedStorage = true;
        analysisWorker.execute(() -> {
            long available;
            try {
                available = getFilesDir().getUsableSpace();
            } catch (SecurityException unavailable) {
                return;
            }
            if (available >= LOW_STORAGE_BYTES) return;
            mainExecutor.execute(() -> {
                if (!resumed || isFinishing() || isDestroyed()) return;
                storageWarning.setVisibility(View.VISIBLE);
                storageWarning.postDelayed(hideStorageWarning, STORAGE_NOTICE_MS);
            });
        });
    }

    private void takePhoto() {
        if (!shutter.isEnabled() || exitAfterShot != null || isFinishing() || isDestroyed()) return;
        if (!isCollection()) {
            captureOriginal(null);
            return;
        }
        boolean sample = samplesCollectionScene();
        if (sample && supportsCollectionSet()) {
            beginCollectionSet("sampled_bracket");
            return;
        }
        String policy = sample ? "sampled_bracket" : session.collectionSingleOnly ? "single_only" : "sampled_normal";
        captureOriginal(new ScanController.CaptureVariant(java.util.UUID.randomUUID().toString(), 1,
                sample ? CollectionExposureSet.SIZE : 1, "normal", policy, sample));
    }

    private void captureOriginal(ScanController.CaptureVariant variant) {
        collectionUnavailableShot = variant != null && variant.unavailableAlternates();
        savedNotice = false;
        status.removeCallbacks(clearSavedNotice);
        cancelThumbnailTask();
        lastSaved.animate().cancel();
        resetThumbnailTransform();
        taking = true;
        cameraNotice = "";
        invalidateSuggestions();
        updateControls();
        CameraExposureTestMode.Setting exposure = confirmedExposure;
        ScanController.CaptureOptions captureOptions = new ScanController.CaptureOptions(
                session == null ? 0 : session.expectedCount, exposure == null ? null : (double) exposure.requestedEv(),
                exposure == null ? null : exposure.index(), exposure == null ? null : exposure.index(),
                exposure == null ? null : exposure.appliedEv(), exposure == null ? null : exposure.step(), variant);
        controller.reserveCapture(retake, captureOptions, target -> {
            if (target == null) {
                completeShot(null);
                return;
            }
            if (!resumed || isFinishing() || isDestroyed() || imageCapture == null) {
                controller.finishCamera(false, this::completeShot);
                return;
            }
            writeJpeg(target);
        });
    }

    private void writeJpeg(File target) {
        // The display may have turned while the file reservation was being committed.
        updateRotation();
        ImageCapture.OutputFileOptions output = new ImageCapture.OutputFileOptions.Builder(target).build();
        try {
            imageCapture.takePicture(output, mainExecutor, new ImageCapture.OnImageSavedCallback() {
                @Override public void onImageSaved(ImageCapture.OutputFileResults result) {
                    commitShot(true);
                }

                @Override public void onError(ImageCaptureException exception) {
                    cameraNotice = "Camera interrupted this shot. Try again.";
                    commitShot(target.length() > 0, true);
                }
            });
        } catch (RuntimeException unavailable) {
            cameraNotice = "Could not take this photo. Try again.";
            commitShot(target.length() > 0, true);
        }
    }

    private void commitShot(boolean keep) {
        commitShot(keep, false);
    }

    private void commitShot(boolean keep, boolean captureFailed) {
        saving = true;
        updateControls();
        controller.finishCamera(keep, captureFailed, this::completeShot);
    }

    private void completeShot(File committed) {
        taking = false;
        saving = false;
        if (isFinishing() || isDestroyed()) return;
        if (collectionSet != null) {
            if (committed == null) collectionSet.stop();
            else {
                collectionSet.committed();
                lastCollectionOriginal = committed;
            }
            advanceCollectionSet();
            return;
        }
        if (committed != null) cameraNotice = collectionUnavailableShot
                ? "Normal original saved · Exposure set unavailable" : "";
        collectionUnavailableShot = false;
        if (retake && committed != null) closeCamera(true, null);
        else if (exitAfterShot != null) closeCamera(exitAfterShot, null);
        else {
            if (resumed && committed != null) showCaptureFeedback(committed);
            updateControls();
        }
    }

    private void showCaptureFeedback(File original) {
        savedNotice = true;
        status.removeCallbacks(clearSavedNotice);
        status.postDelayed(clearSavedNotice, SAVED_NOTICE_MS);
        cancelThumbnailTask();
        hideThumbnail();
        thumbnailSourceId = original.getName();
        lastSaved.setContentDescription("Check captured scan");
        int generation = thumbnailGeneration;
        thumbnailTask = thumbnailWorker.submit(() -> {
            Bitmap preview;
            try {
                preview = ImageEngine.preview(original, 192);
            } catch (Exception | OutOfMemoryError unavailable) {
                return;
            }
            mainExecutor.execute(() -> {
                if (generation != thumbnailGeneration || !resumed || isDestroyed() || isFinishing()) {
                    preview.recycle();
                    return;
                }
                lastSaved.animate().cancel();
                lastSaved.setImageBitmap(preview);
                lastSaved.setVisibility(View.VISIBLE);
                resetThumbnailTransform();
                if (!isCollection()) animateSavedThumbnail();
            });
        });
    }

    private void cancelThumbnailTask() {
        thumbnailGeneration++;
        if (thumbnailTask != null) {
            thumbnailTask.cancel(true);
            thumbnailTask = null;
            thumbnailWorker.purge();
        }
    }

    private void clearCaptureFeedback() {
        cancelThumbnailTask();
        status.removeCallbacks(clearSavedNotice);
        savedNotice = false;
        hideThumbnail();
    }

    private void hideThumbnail() {
        thumbnailSourceId = "";
        lastSaved.animate().cancel();
        resetThumbnailTransform();
        lastSaved.setImageDrawable(null);
        lastSaved.setVisibility(View.INVISIBLE);
    }

    private void resetThumbnailTransform() {
        lastSaved.setTranslationX(0);
        lastSaved.setTranslationY(0);
        lastSaved.setScaleX(1);
        lastSaved.setScaleY(1);
    }

    private void animateSavedThumbnail() {
        int width = lastSaved.getWidth();
        int height = lastSaved.getHeight();
        if (!ValueAnimator.areAnimatorsEnabled() || width == 0 || height == 0) return;
        float scale = Math.min(2.4f, Math.min(viewfinder.getWidth() * .38f / width,
                viewfinder.getHeight() * .36f / height));
        if (scale <= 1) return;
        boolean rtl = lastSaved.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        lastSaved.setPivotX(rtl ? 0 : width);
        lastSaved.setPivotY(height / 2f);
        lastSaved.setTranslationX(dp(rtl ? 12 : -12));
        lastSaved.setTranslationY(height * (1 + scale) / 2f + dp(15));
        lastSaved.setScaleX(scale);
        lastSaved.setScaleY(scale);
        lastSaved.animate().translationX(0).translationY(0).scaleX(1).scaleY(1).setDuration(280).start();
    }

    private void requestExit(boolean openReview) {
        if (collectionSet != null) {
            exitAfterShot = openReview;
            stopCollectionSet();
        } else if (taking) {
            exitAfterShot = openReview;
            updateControls();
        } else closeCamera(openReview, null);
    }

    private void closeCamera(boolean openReview, String notice) {
        closeCamera(openReview, notice, false);
    }

    private void closeCamera(boolean openReview, String notice, boolean openImport) {
        closeCamera(openReview, notice, openImport, "");
    }

    private void closeCamera(boolean openReview, String notice, boolean openImport, String sourceId) {
        controller.detachCamera(this);
        Intent result = new Intent().putExtra(EXTRA_REVIEW, openReview)
                .putExtra(EXTRA_RETAKE, retake).putExtra(EXTRA_IMPORT, openImport);
        if (!sourceId.isEmpty()) result.putExtra(EXTRA_REVIEW_SOURCE_ID, sourceId);
        if (notice != null) result.putExtra(EXTRA_NOTICE, notice);
        setResult(RESULT_OK, result);
        finish();
    }

    private void startLevel() {
        hasGravitySample = false;
        aligned = false;
        levelHint.setTextColor(MUTED);
        if (sensorManager != null) {
            Sensor sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
            gravitySensor = sensor != null && sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI);
            if (!gravitySensor) {
                sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                if (sensor != null && sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)) {
                    levelHint.setText("Level over a flat page");
                    return;
                }
            } else {
                levelHint.setText("Level over a flat page");
                return;
            }
        }
        levelHint.setText("Level unavailable");
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (!resumed || event.values.length < 3) return;
        for (int i = 0; i < 3; i++) {
            gravity[i] = gravitySensor || !hasGravitySample
                    ? event.values[i] : 0.82f * gravity[i] + 0.18f * event.values[i];
        }
        hasGravitySample = true;
        updateLevel();
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void updateLevel() {
        float[] tilt = LevelMath.flatTilt(gravity[0], gravity[1], gravity[2], rotation);
        guides.showTilt(tilt);
        boolean level = LevelMath.isLevel(tilt);
        if (level == aligned) return;
        aligned = level;
        levelHint.setText(level ? "Level over a flat page · aligned" : "Level over a flat page");
        levelHint.setTextColor(level ? 0xFF90E7AF : MUTED);
    }

    private void updateControls() {
        int pages = session == null ? 0 : session.pageCount;
        boolean pending = session != null && !session.pending.isEmpty();
        boolean collection = isCollection();
        cameraTitle.setText(collection ? "Collect originals" : retake ? "Retake page" : "Scan pages");
        count.setText(collection ? session.physicalBookLabel + "\n" + pages + (pages == 1 ? " original saved" : " originals saved")
                : pages + (pages == 1 ? " scan" : " scans")
                + (session != null && session.queuedCount > 0 ? " · " + session.queuedCount + " processing" : ""));
        String shutterLabel = collection && (collectionSet != null || samplesCollectionScene() && supportsCollectionSet())
                ? "Take 3 exposures" : "Take photo";
        shutter.setText(shutterLabel);
        shutter.setContentDescription(shutterLabel);
        shutter.setEnabled(resumed && streaming && imageCapture != null && sessionReady && !sessionBusy && !pending
                && !taking && collectionSet == null && !exposureRequests.pending() && !exposureBlocked
                && (!collection || requestedExposureEv == 0 && (confirmedExposure == null || confirmedExposure.index() == 0)));
        done.setText(collectionSet != null ? "Stop set" : retake ? "Cancel" : "Done");
        done.setEnabled(exitAfterShot == null);
        importPhotos.setText(collection ? "New physical album" : "Import photos");
        importPhotos.setEnabled(!taking && !pending && exitAfterShot == null && collectionSet == null
                && (!collection || !sessionBusy));
        analysisActive = session != null && !collection && resumed && streaming && liveAvailable && !taking;
        glareToggle.setVisibility(collection ? View.GONE : View.VISIBLE);
        options.setEnabled(sessionReady && !sessionBusy && !taking && !pending && collectionSet == null && !exposureRequests.pending());
        int expected = session == null ? 0 : session.expectedCount;
        options.setText(collection ? "Collection options" : expected == 0 ? "Scan options" : "Expected: " + expected);
        updateScanResults();
        String exposure = exposureRequests.pending() ? "Setting exposure…"
                : !exposureError.isEmpty() ? exposureError
                : confirmedExposure != null && confirmedExposure.index() != 0
                ? "Exposure test: " + exposureLabel(confirmedExposure.appliedEv()) : "";
        exposureStatus.setText(exposure);
        exposureStatus.setVisibility(exposure.isEmpty() ? View.GONE : View.VISIBLE);

        String message = cameraNotice;
        if (collectionSet != null) {
            message = "Exposure set · " + collectionSet.saved() + " of " + CollectionExposureSet.SIZE + " saved";
            if (restoringCollectionExposure) message += " · Restoring normal";
        } else if (exitAfterShot != null) message = "Finishing this shot…";
        else if (taking) message = saving ? "Saving photo…" : "Taking photo…";
        else if (pending) message = "Return to the batch to recover the previous shot.";
        else if (savedNotice && !collection) message = "Photo saved";
        else if (message.isEmpty() && !sessionReady && !sessionNotice.isEmpty()) message = sessionNotice;
        else if (message.isEmpty() && !streaming) message = "Opening camera…";
        else if (message.isEmpty() && glareHint.visible()) message = "Possible glare · tilt slightly";
        else if (message.isEmpty() && softWarning) message = "Image looks soft. Hold steady before shooting.";
        else if (message.isEmpty()) message = sessionNotice;
        status.setText(message);
        status.setVisibility(message.isEmpty() ? collection && landscapeControls ? View.GONE : View.INVISIBLE : View.VISIBLE);
    }

    private void updateScanResults() {
        var newest = session == null || retake || isCollection() ? null : session.latestScan;
        var previous = session == null ? null : session.latestCompleted;
        if (newest == null || !"pending".equals(newest.status)
                || previous != null && newest.sourceId.equals(previous.sourceId)) previous = null;
        boolean resultsChanged = setResultLabel(latestResult, newest);
        resultsChanged = setResultLabel(earlierResult, previous) || resultsChanged;
        var check = newest != null && !"pending".equals(newest.status) ? newest : previous;
        checkScan.setVisibility(check == null || retake ? View.GONE : View.VISIBLE);
        if (check != null) {
            boolean needsCheck = check.mismatch || "failure".equals(check.status)
                    || check.detectedCount != null && check.detectedCount == 0;
            checkScan.setText((needsCheck ? "Check scan " : "View scan ") + check.pageNumber);
            String id = check.sourceId;
            checkScan.setOnClickListener(view -> reviewScan(id));
        }
        checkScan.setEnabled(!taking && !sessionBusy);
        lastSaved.setEnabled(!retake && !taking && !sessionBusy && collectionSet == null);
        if (landscapeControls && resultsChanged) railScroll.post(() -> railScroll.scrollTo(0, 0));
    }

    private boolean setResultLabel(TextView view, ScanController.CountSummary result) {
        int visibility = result == null ? View.GONE : View.VISIBLE;
        boolean visibilityChanged = view.getVisibility() != visibility;
        view.setVisibility(visibility);
        if (result == null) return visibilityChanged;
        String description;
        if ("pending".equals(result.status)) description = "Finding photos…";
        else if ("failure".equals(result.status)) description = "Detection failed";
        else if (result.detectedCount == null) description = "Count unavailable";
        else if (result.detectedCount == 0) description = "No photos found";
        else description = result.detectedCount + (result.detectedCount == 1 ? " photo found" : " photos found");
        if (result.mismatch) description += " · expected " + result.expectedCount;
        String message = "Scan " + result.pageNumber + " · " + description;
        if (TextUtils.equals(view.getText(), message)) return visibilityChanged;
        view.setText(message);
        return true;
    }

    private void reviewScan(String sourceId) {
        if (sourceId.isEmpty() || taking || sessionBusy || retake || collectionSet != null || isFinishing()) return;
        closeCamera(false, null, false, sourceId);
    }

    private void openScanOptions() {
        if (isCollection()) {
            openCollectionOptions();
            return;
        }
        var menu = new android.widget.PopupMenu(this, options);
        menu.getMenu().add("Expected photos per scan").setOnMenuItemClickListener(item -> {
            String[] choices = {"Auto", "1", "2", "3", "4", "5", "6"};
            new AlertDialog.Builder(this).setTitle("Expected photos per scan")
                    .setSingleChoiceItems(choices, session.expectedCount, (dialog, value) -> {
                        controller.setExpectedCount(value);
                        dialog.dismiss();
                    }).setNegativeButton("Cancel", null).show();
            return true;
        });
        menu.getMenu().add("Developer tools").setOnMenuItemClickListener(item -> {
            openDeveloperTools();
            return true;
        });
        menu.show();
    }

    private boolean isCollection() { return session != null && session.collection; }

    private boolean samplesCollectionScene() {
        return isCollection() && CollectionExposureSet.samples(session.nextCollectionSceneOrdinal, session.collectionSingleOnly);
    }

    private boolean supportsCollectionSet() {
        return exposureSetting(0) != null && exposureSetting(-1) != null && exposureSetting(1) != null;
    }

    private void openCollectionOptions() {
        var menu = new android.widget.PopupMenu(this, options);
        boolean bracketSupported = supportsCollectionSet();
        menu.getMenu().add(bracketSupported ? "Capture 3 exposures" : "3 exposures unavailable")
                .setEnabled(bracketSupported && shutter.isEnabled())
                .setOnMenuItemClickListener(item -> {
                    beginCollectionSet("forced_bracket");
                    return true;
                });
        menu.getMenu().add(session.collectionSingleOnly ? "Use sampled exposure sets" : "Single photos only")
                .setOnMenuItemClickListener(item -> {
                    controller.setCollectionSingleOnly(!session.collectionSingleOnly);
                    return true;
                });
        menu.getMenu().add("Switch physical album").setEnabled(session.physicalBooks.size() > 1)
                .setOnMenuItemClickListener(item -> {
                    var books = session.physicalBooks;
                    String[] labels = new String[books.size()];
                    int selected = -1;
                    for (int i = 0; i < books.size(); i++) {
                        labels[i] = books.get(i).label();
                        if (books.get(i).id().equals(session.physicalBookId)) selected = i;
                    }
                    new AlertDialog.Builder(this).setTitle("Switch physical album")
                            .setSingleChoiceItems(labels, selected, (dialog, index) -> {
                                controller.switchPhysicalBook(books.get(index).id());
                                dialog.dismiss();
                            }).setNegativeButton("Cancel", null).show();
                    return true;
                });
        menu.show();
    }

    private void beginCollectionSet(String policy) {
        if (!shutter.isEnabled() || collectionSet != null || !supportsCollectionSet()) return;
        collectionSet = new CollectionExposureSet(policy);
        lastCollectionOriginal = null;
        cameraNotice = "";
        revealCollectionStatus();
        advanceCollectionSet();
    }

    private void stopCollectionSet() {
        collectionSet.stop();
        if (!taking && !exposureRequests.pending() && !restoringCollectionExposure) advanceCollectionSet();
        else updateControls();
    }

    private void advanceCollectionSet() {
        CollectionExposureSet set = collectionSet;
        if (set == null || restoringCollectionExposure) return;
        if (!resumed) set.stop();
        if (!set.hasNext()) {
            restoreAfterCollectionSet(set);
            return;
        }
        int ev = set.nextEv();
        applyExposure(ev, false, success -> {
            if (!success || !resumed) {
                if (!success && set.saved() > 0 && resumed) controller.markCollectionVariantUnavailable(collectionVariant(set));
                set.stop();
            }
            if (set.hasNext()) captureCollectionFrame(set);
            else restoreAfterCollectionSet(set);
        });
    }

    private void captureCollectionFrame(CollectionExposureSet set) {
        captureOriginal(collectionVariant(set));
    }

    private ScanController.CaptureVariant collectionVariant(CollectionExposureSet set) {
        return new ScanController.CaptureVariant(set.id, set.saved() + 1, CollectionExposureSet.SIZE,
                set.nextRole(), set.policy, false);
    }

    private void restoreAfterCollectionSet(CollectionExposureSet set) {
        if (confirmedExposure != null && confirmedExposure.index() == 0 && !exposureBlocked) {
            finishCollectionSet(set);
            return;
        }
        restoringCollectionExposure = true;
        applyExposure(0, false, restored -> finishCollectionSet(set));
    }

    private void finishCollectionSet(CollectionExposureSet set) {
        collectionSet = null;
        restoringCollectionExposure = false;
        cameraNotice = set.saved() == CollectionExposureSet.SIZE ? "Exposure set saved · 3 originals"
                : "Exposure set incomplete · " + set.saved() + " of " + CollectionExposureSet.SIZE + " saved";
        File preview = lastCollectionOriginal;
        lastCollectionOriginal = null;
        if (exitAfterShot != null) closeCamera(exitAfterShot, cameraNotice);
        else {
            if (resumed && preview != null) showCaptureFeedback(preview);
            updateControls();
            revealCollectionStatus();
        }
    }

    private void revealCollectionStatus() {
        if (landscapeControls) railScroll.post(() -> railScroll.scrollTo(0, 0));
    }

    private void openDeveloperTools() {
        String outlines = liveOutlines ? "Hide live outlines" : "Show live outlines";
        new AlertDialog.Builder(this).setTitle("Developer tools")
                .setItems(new String[]{outlines, "Exposure compensation"}, (dialog, index) -> {
                    if (index == 0) {
                        if (!liveAvailable) {
                            cameraNotice = "Live outlines unavailable on this camera.";
                        } else {
                            liveOutlines = !liveOutlines;
                            guides.setOutlinesVisible(liveOutlines);
                            invalidateSuggestions();
                        }
                        updateControls();
                    } else openExposureOptions();
                }).setNegativeButton("Close", null).show();
    }

    private CameraExposureTestMode.Setting exposureSetting(int ev) {
        if (camera == null) return null;
        var state = camera.getCameraInfo().getExposureState();
        if (!state.isExposureCompensationSupported()) return null;
        var range = state.getExposureCompensationRange();
        return CameraExposureTestMode.select(ev, range.getLower(), range.getUpper(), state.getExposureCompensationStep().doubleValue());
    }

    private void openExposureOptions() {
        if (exposureSetting(0) == null) {
            new AlertDialog.Builder(this).setTitle("Exposure compensation")
                    .setMessage("Exposure compensation is unavailable on this camera.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        List<String> labels = new ArrayList<>();
        List<Integer> values = new ArrayList<>();
        for (int ev : new int[]{0, -1, 1}) {
            var setting = exposureSetting(ev);
            if (setting == null) continue;
            labels.add(ev == 0 ? "Normal (0 EV)" : (ev < 0 ? "Darker (" : "Brighter (")
                    + exposureLabel(setting.appliedEv()) + ")");
            values.add(ev);
        }
        new AlertDialog.Builder(this).setTitle("Exposure compensation")
                .setSingleChoiceItems(labels.toArray(new String[0]), values.indexOf(requestedExposureEv), (dialog, which) -> {
                    applyExposure(values.get(which));
                    dialog.dismiss();
                }).setNegativeButton("Cancel", null).show();
    }

    private static String exposureLabel(double ev) {
        return String.format(Locale.US, "%+.2f EV", ev).replaceAll("\\.?0+ EV$", " EV");
    }

    private void applyExposure(int ev) {
        applyExposure(ev, false);
    }

    private void applyExposure(int ev, boolean recovering) {
        applyExposure(ev, recovering, null);
    }

    private void applyExposure(int ev, boolean recovering, Consumer<Boolean> completion) {
        var setting = exposureSetting(ev);
        if (setting == null) {
            requestedExposureEv = 0;
            confirmedExposure = null;
            exposureError = "Exposure compensation unavailable.";
            updateControls();
            if (completion != null) completion.accept(false);
            return;
        }
        requestedExposureEv = ev;
        exposureError = "";
        int request = exposureRequests.begin();
        updateControls();
        ListenableFuture<Integer> result;
        try {
            result = camera.getCameraControl().setExposureCompensationIndex(setting.index());
        } catch (RuntimeException failure) {
            if (exposureRequests.finish(request)) exposureFailed(ev, completion);
            return;
        }
        cameraLayout.postDelayed(() -> {
            if (isDestroyed() || isFinishing() || !exposureRequests.finish(request)) return;
            result.cancel(false);
            exposureFailed(ev, completion);
        }, EXPOSURE_TIMEOUT_MS);
        result.addListener(() -> {
            if (isFinishing() || isDestroyed() || !exposureRequests.finish(request)) return;
            try {
                if (result.get() != setting.index()) throw new IllegalStateException("Unexpected exposure index");
                confirmedExposure = setting;
                exposureBlocked = false;
                if (recovering) exposureError = "Exposure test failed. Normal exposure restored.";
                updateControls();
            } catch (Exception failure) {
                exposureFailed(ev, completion);
                return;
            }
            if (completion != null) completion.accept(true);
        }, mainExecutor);
    }

    private void exposureFailed(int ev, Consumer<Boolean> completion) {
        confirmedExposure = null;
        exposureBlocked = true;
        if (completion != null) {
            if (ev == 0) {
                requestedExposureEv = 0;
                exposureError = "Exposure reset failed. Reopen camera to retry.";
            }
            completion.accept(false);
            return;
        }
        if (ev != 0) {
            // An unconfirmed request can still apply. A new zero request supersedes it.
            applyExposure(0, true);
            return;
        }
        requestedExposureEv = 0;
        exposureError = "Exposure reset failed. Select Normal to retry.";
        updateControls();
    }

    private void makeLayout() {
        LinearLayout root = cameraLayout = new LinearLayout(this);
        root.setClipChildren(false);
        root.setBackgroundColor(BACKGROUND);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });

        header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setClipChildren(false);
        header.setClipToPadding(false);
        header.setElevation(dp(1));
        header.setPadding(dp(16), dp(9), dp(16), dp(8));
        heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setClipChildren(false);
        heading.setClipToPadding(false);
        header.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout headerText = new LinearLayout(this);
        headerText.setOrientation(LinearLayout.VERTICAL);
        cameraTitle = text(retake ? "Retake page" : "Scan pages", 22, TEXT, true);
        cameraTitle.setAccessibilityHeading(true);
        headerText.addView(cameraTitle);
        count = text("0 pages in this batch", 14, MUTED, false);
        headerText.addView(count);
        heading.addView(headerText, new LinearLayout.LayoutParams(0, -2, 1f));
        glareToggle = new Switch(this);
        glareToggle.setText("Glare hints");
        glareToggle.setTextColor(TEXT);
        glareToggle.setTextSize(12);
        glareToggle.setMinHeight(dp(48));
        glareToggle.setSwitchPadding(dp(4));
        int[][] checked = {{android.R.attr.state_checked}, {}};
        glareToggle.setThumbTintList(new ColorStateList(checked, new int[]{0xFF9BE9D6, MUTED}));
        glareToggle.setTrackTintList(new ColorStateList(checked, new int[]{0xFF618E85, 0xFF536C68}));
        glareToggle.setChecked(glareEnabled);
        glareToggle.setOnCheckedChangeListener((button, enabled) -> {
            glareEnabled = enabled;
            glareSettingGeneration++;
            getSharedPreferences(CAMERA_PREFERENCES, MODE_PRIVATE).edit()
                    .putBoolean(GLARE_PREFERENCE, enabled).apply();
            glareHint.clear();
            updateControls();
        });
        lastSaved = new ImageView(this);
        lastSaved.setContentDescription("Last saved photo");
        lastSaved.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable thumbnailFrame = new GradientDrawable();
        thumbnailFrame.setColor(BACKGROUND);
        thumbnailFrame.setCornerRadius(dp(7));
        thumbnailFrame.setStroke(dp(1), MUTED);
        lastSaved.setBackground(thumbnailFrame);
        lastSaved.setClipToOutline(true);
        lastSaved.setPadding(dp(2), dp(2), dp(2), dp(2));
        lastSaved.setVisibility(View.INVISIBLE);
        lastSaved.setOnClickListener(view -> reviewScan(thumbnailSourceId));
        LinearLayout.LayoutParams thumbnailSize = new LinearLayout.LayoutParams(dp(56), dp(48));
        thumbnailSize.setMarginStart(dp(8));
        heading.addView(lastSaved, thumbnailSize);
        scanResults = new LinearLayout(this);
        scanResults.setOrientation(LinearLayout.VERTICAL);
        latestResult = text("", 15, TEXT, true);
        latestResult.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        latestResult.setVisibility(View.GONE);
        scanResults.addView(latestResult);
        earlierResult = text("", 13, MUTED, false);
        earlierResult.setVisibility(View.GONE);
        scanResults.addView(earlierResult);
        header.addView(scanResults);
        exposureStatus = text("", 13, TEXT, true);
        exposureStatus.setVisibility(View.GONE);
        header.addView(exposureStatus);

        previewFrame = new FrameLayout(this);
        previewFrame.setBackgroundColor(0xFF071212);
        viewfinder = new PreviewView(this);
        viewfinder.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        viewfinder.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        viewfinder.setContentDescription("Live camera preview");
        previewFrame.addView(viewfinder, new FrameLayout.LayoutParams(-1, -1));
        guides = new CameraGuideView(this, this::clearSuggestionIndicators);
        guides.setOutlinesVisible(liveOutlines);
        previewFrame.addView(guides, new FrameLayout.LayoutParams(-1, -1));
        storageWarning = text("Phone storage is low. Free space before scanning many pages.", 13, TEXT, true);
        storageWarning.setGravity(Gravity.CENTER);
        storageWarning.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable storageBackground = new GradientDrawable();
        storageBackground.setColor(0xF219302E);
        storageBackground.setCornerRadius(dp(8));
        storageWarning.setBackground(storageBackground);
        storageWarning.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        storageWarning.setVisibility(View.GONE);
        FrameLayout.LayoutParams storagePosition = new FrameLayout.LayoutParams(-2, -2,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        storagePosition.setMargins(dp(12), dp(10), dp(12), 0);
        previewFrame.addView(storageWarning, storagePosition);
        root.addView(previewFrame);

        footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(dp(12), dp(5), dp(12), dp(7));
        hints = new LinearLayout(this);
        hints.setGravity(Gravity.CENTER_VERTICAL);
        options = button("Scan options", false, this::openScanOptions);
        options.setTextSize(13);
        hints.addView(options);
        levelHint = text("Level over a flat page", 13, MUTED, false);
        levelHint.setGravity(Gravity.END);
        hints.addView(levelHint, new LinearLayout.LayoutParams(0, -2, 1f));
        footer.addView(hints);
        checkScan = button("Check scan", false, () -> {});
        checkScan.setTextSize(13);
        checkScan.setVisibility(View.GONE);
        footer.addView(checkScan, new LinearLayout.LayoutParams(-2, -2));

        status = text("Opening camera…", 13, MUTED, false);
        status.setId(R.id.camera_capture_status);
        status.setLines(1);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        footer.addView(status);

        buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setBaselineAligned(false);
        secondaryButtons = new LinearLayout(this);
        secondaryButtons.setBaselineAligned(false);
        secondaryButtons.setGravity(Gravity.CENTER_VERTICAL);
        importPhotos = button("Import photos", false, () -> {
            if (isCollection()) controller.newPhysicalBook("");
            else closeCamera(false, null, true);
        });
        if (retake) importPhotos.setVisibility(View.GONE);
        shutter = button("Take photo", true, this::takePhoto);
        shutter.setTextSize(18);
        shutter.setMinHeight(dp(72));
        shutter.setMinimumHeight(dp(72));
        shutter.setContentDescription("Take photo");
        done = button(retake ? "Cancel" : "Done", false, () -> {
            if (collectionSet != null) stopCollectionSet();
            else requestExit(!retake);
        });
        footer.addView(buttons);
        rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setClipChildren(false);
        railScroll = new ScrollView(this);
        railScroll.setFillViewport(true);
        railScroll.addView(rail);
        landscapePanel = new LinearLayout(this);
        landscapePanel.setOrientation(LinearLayout.VERTICAL);
        landscapePanel.addView(railScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        layoutCamera(getResources().getConfiguration());
        setContentView(root);
        viewfinder.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                updateRotation();
                invalidateSuggestions();
            }
        });
        viewfinder.getPreviewStreamState().observe(this, state -> {
            streaming = state == PreviewView.StreamState.STREAMING;
            if (!streaming) invalidateSuggestions();
            updateControls();
        });
    }

    private void layoutCamera(Configuration configuration) {
        boolean landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                && configuration.screenWidthDp >= 480;
        if (header.getParent() != null && landscapeControls == landscape) return;
        landscapeControls = landscape;
        cameraLayout.removeView(header);
        cameraLayout.removeView(footer);
        cameraLayout.removeView(landscapePanel);
        rail.removeAllViews();
        heading.removeView(glareToggle);
        header.removeView(glareToggle);
        header.removeView(scanResults);
        scanResults.removeView(checkScan);
        footer.removeView(checkScan);
        footer.removeView(buttons);
        landscapePanel.removeView(buttons);
        buttons.removeAllViews();
        secondaryButtons.removeAllViews();
        lastSaved.animate().cancel();
        resetThumbnailTransform();
        hints.setOrientation(landscape ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        options.setLayoutParams(new LinearLayout.LayoutParams(landscape ? -1 : -2, -2));
        levelHint.setLayoutParams(new LinearLayout.LayoutParams(landscape ? -1 : 0, -2, landscape ? 0 : 1));
        levelHint.setGravity(landscape ? Gravity.START : Gravity.END);

        if (landscape) {
            cameraLayout.setOrientation(LinearLayout.HORIZONTAL);
            previewFrame.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1f));
            scanResults.addView(checkScan, new LinearLayout.LayoutParams(-2, -2));
            header.addView(scanResults, 0);
            header.addView(glareToggle, new LinearLayout.LayoutParams(-2, -2));
            buttons.setOrientation(LinearLayout.VERTICAL);
            buttons.setPadding(dp(12), 0, dp(12), dp(7));
            LinearLayout.LayoutParams shutterParams = buttonParams(-1, 0);
            shutterParams.bottomMargin = dp(7);
            buttons.addView(shutter, shutterParams);
            secondaryButtons.addView(importPhotos, buttonParams(0, 1));
            secondaryButtons.addView(done, buttonParams(0, 1));
            buttons.addView(secondaryButtons, new LinearLayout.LayoutParams(-1, -2));
            status.setLines(2);
            rail.addView(header, new LinearLayout.LayoutParams(-1, -2));
            rail.addView(footer, new LinearLayout.LayoutParams(-1, -2));
            landscapePanel.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
            cameraLayout.addView(landscapePanel, new LinearLayout.LayoutParams(dp(224), -1));
        } else {
            cameraLayout.setOrientation(LinearLayout.VERTICAL);
            previewFrame.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
            header.addView(scanResults, 1);
            footer.addView(checkScan, 1, new LinearLayout.LayoutParams(-2, -2));
            heading.addView(glareToggle, 1, new LinearLayout.LayoutParams(-2, -2));
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.setPadding(0, 0, 0, 0);
            buttons.addView(importPhotos, buttonParams(0, 1));
            LinearLayout.LayoutParams shutterParams = buttonParams(0, 2);
            shutterParams.setMarginStart(dp(9));
            shutterParams.setMarginEnd(dp(9));
            buttons.addView(shutter, shutterParams);
            buttons.addView(done, buttonParams(0, 1));
            status.setLines(1);
            footer.addView(buttons);
            cameraLayout.addView(header, 0, new LinearLayout.LayoutParams(-1, -2));
            cameraLayout.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        }
        positionCaptureStatus();
    }

    private void positionCaptureStatus() {
        LinearLayout target = landscapeControls && isCollection() ? header : footer;
        if (status.getParent() == target) return;
        if (status.getParent() instanceof ViewGroup oldParent) oldParent.removeView(status);
        int index = target == header ? 0 : footer.indexOfChild(buttons);
        target.addView(status, index < 0 ? target.getChildCount() : index);
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String value, boolean primary, Runnable action) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextSize(14);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(5), dp(4), dp(5), dp(4));
        int[] enabled = {android.R.attr.state_enabled};
        button.setTextColor(new ColorStateList(new int[][]{enabled, new int[]{}},
                new int[]{primary ? 0xFF092E2A : TEXT, 0xFF8BA4A0}));
        GradientDrawable background = new GradientDrawable();
        background.setColor(new ColorStateList(new int[][]{enabled, new int[]{}},
                new int[]{primary ? 0xFF9BE9D6 : 0xFF2A4B47, 0xFF29413E}));
        background.setCornerRadius(dp(12));
        button.setBackground(background);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private LinearLayout.LayoutParams buttonParams(int width, float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, -2, weight);
        params.setMargins(dp(3), dp(5), dp(3), dp(5));
        return params;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
