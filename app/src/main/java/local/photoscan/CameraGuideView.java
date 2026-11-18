package local.photoscan;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;

import java.util.List;

final class CameraGuideView extends View {
    private static final String[] NUMBERS = {"1", "2", "3", "4", "5", "6"};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final LiveOutlineMotion motion = new LiveOutlineMotion();
    private final Runnable onSuggestionsExpired;
    private final Runnable expireSuggestions = this::expireSuggestions;
    private final float density;
    private float[] tilt;
    private boolean outlinesVisible;

    CameraGuideView(Context context, Runnable onSuggestionsExpired) {
        super(context);
        this.onSuggestionsExpired = onSuggestionsExpired;
        density = getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    boolean showSuggestions(List<float[]> quads, long measuredAt) {
        long now = SystemClock.elapsedRealtime();
        removeCallbacks(expireSuggestions);
        motion.update(quads, now, measuredAt, density);
        if (motion.expiresAt() > now) postDelayed(expireSuggestions, motion.expiresAt() - now);
        invalidate();
        return motion.expiresAt() > now;
    }

    void clearSuggestions() {
        removeCallbacks(expireSuggestions);
        motion.clear();
        invalidate();
    }

    private void expireSuggestions() {
        if (motion.expire(SystemClock.elapsedRealtime())) {
            invalidate();
            onSuggestionsExpired.run();
        }
    }

    @Override protected void onDetachedFromWindow() {
        clearSuggestions();
        super.onDetachedFromWindow();
    }

    void showTilt(float[] tilt) {
        this.tilt = tilt;
        invalidate();
    }

    void setOutlinesVisible(boolean visible) {
        outlinesVisible = visible;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        expireSuggestions();
        if (outlinesVisible) {
            List<float[]> quads = motion.sample(SystemClock.elapsedRealtime());
            for (int i = 0; i < quads.size(); i++) drawQuad(canvas, quads.get(i), i + 1);
            if (motion.isAnimating()) postInvalidateOnAnimation();
        }
        if (tilt != null) {
            float x = getWidth() / 2f;
            float y = getHeight() / 2f;
            crosshair(canvas, x, y, 15, 0xAAFFFFFF);
            int color = LevelMath.isLevel(tilt) ? 0xFF90E7AF : 0xFFFAE9BD;
            crosshair(canvas, x + dp(2.5f * clamp(tilt[0])), y + dp(2.5f * clamp(tilt[1])), 9, color);
        }
    }

    private void drawQuad(Canvas canvas, float[] points, int number) {
        path.reset();
        path.moveTo(points[0], points[1]);
        float centerX = points[0];
        float centerY = points[1];
        for (int i = 2; i < 8; i += 2) {
            path.lineTo(points[i], points[i + 1]);
            centerX += points[i];
            centerY += points[i + 1];
        }
        path.close();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(4));
        paint.setColor(0xAD001A1A);
        canvas.drawPath(path, paint);
        paint.setStrokeWidth(dp(2));
        paint.setColor(0xFF56DECD);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(centerX / 4, centerY / 4, dp(12), paint);
        paint.setColor(0xFF092F2C);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(dp(13));
        paint.setFakeBoldText(true);
        canvas.drawText(NUMBERS[number - 1], centerX / 4, centerY / 4 + dp(4.5f), paint);
        paint.setFakeBoldText(false);
    }

    private void crosshair(Canvas canvas, float x, float y, float size, int color) {
        float length = dp(size);
        float gap = dp(2.5f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(3.5f));
        paint.setColor(0x78000000);
        crosshairLines(canvas, x, y, length, gap);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(color);
        crosshairLines(canvas, x, y, length, gap);
    }

    private void crosshairLines(Canvas canvas, float x, float y, float length, float gap) {
        canvas.drawLine(x - length, y, x - gap, y, paint);
        canvas.drawLine(x + gap, y, x + length, y, paint);
        canvas.drawLine(x, y - length, x, y - gap, paint);
        canvas.drawLine(x, y + gap, x, y + length, paint);
    }

    private float dp(float value) { return value * density; }
    private static float clamp(float value) { return Math.max(-12, Math.min(12, value)); }
}
