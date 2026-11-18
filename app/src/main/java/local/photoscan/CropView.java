package local.photoscan;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class CropView extends View {
    interface Listener {
        void select(int crop);
        void move(float[] corners);
    }

    private static final int CORNER_ACTION = 0x00200010;
    private static final int LEFT_ACTION = 0x00200011;
    private static final int RIGHT_ACTION = 0x00200012;
    private static final int UP_ACTION = 0x00200013;
    private static final int DOWN_ACTION = 0x00200014;
    private static final float MAX_ZOOM = 5f;
    private static final String[] CORNER_NAMES = {"top left", "top right", "bottom right", "bottom left"};
    private final RectF image = new RectF();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path outline = new Path();
    private final Path loupeClip = new Path();
    private final Rect loupeSource = new Rect();
    private final RectF loupeWindow = new RectF();
    private final float density;
    private final Listener listener;
    private Bitmap bitmap;
    private List<float[]> crops = Collections.emptyList();
    private int selected = -1;
    private int handle = -1;
    private int accessibleCorner;
    private float[] drag;
    private float touchX;
    private float touchY;
    private float zoom = 1f;
    private float panX;
    private float panY;
    private float lastX;
    private float lastY;
    private float pinchSpan;
    private boolean multiTouch;
    private boolean panning;

    CropView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        density = getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setFocusable(true);
        setClickable(true);
    }

    void show(Bitmap bitmap, List<float[]> crops, int selected) {
        boolean newPage = this.bitmap != bitmap;
        boolean sameCrop = !newPage && this.selected == selected && selected >= 0
                && Arrays.equals(this.crops.get(selected), crops.get(selected));
        this.bitmap = bitmap;
        this.crops = crops;
        this.selected = selected;
        if (!sameCrop) {
            drag = null;
            handle = -1;
        }
        if (newPage) fitPage();
        setContentDescription(selected >= 0
                ? "Page photo. Crop " + (selected + 1) + " selected. Editing " + CORNER_NAMES[accessibleCorner] + " corner."
                : "Page photo. No crop selected.");
        invalidate();
    }

    void fitPage() {
        zoom = 1f;
        panX = panY = 0;
        drag = null;
        handle = -1;
        pinchSpan = 0;
        panning = true;
        updateImageBounds();
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        updateImageBounds();
        if (image.isEmpty()) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        canvas.drawBitmap(bitmap, null, image, paint);
        for (int i = 0; i < crops.size(); i++) if (i != selected) drawCrop(canvas, crops.get(i), i, false);
        if (selected >= 0) drawCrop(canvas, drag == null ? crops.get(selected) : drag, selected, true);
        if (drag != null && handle >= 0) drawLoupe(canvas);
    }

    private void updateImageBounds() {
        float margin = dp(10);
        if (bitmap == null || getWidth() <= 2 * margin || getHeight() <= 2 * margin) {
            image.setEmpty();
            return;
        }
        float fit = Math.min((getWidth() - 2 * margin) / bitmap.getWidth(),
                (getHeight() - 2 * margin) / bitmap.getHeight());
        float width = bitmap.getWidth() * fit * zoom;
        float height = bitmap.getHeight() * fit * zoom;
        float maxPanX = Math.max(0, (width - getWidth()) / 2 + margin);
        float maxPanY = Math.max(0, (height - getHeight()) / 2 + margin);
        panX = Math.max(-maxPanX, Math.min(maxPanX, panX));
        panY = Math.max(-maxPanY, Math.min(maxPanY, panY));
        float left = (getWidth() - width) / 2 + panX;
        float top = (getHeight() - height) / 2 + panY;
        image.set(left, top, left + width, top + height);
    }

    private void drawLoupe(Canvas canvas) {
        float side = Math.min(dp(116), Math.min(getWidth(), getHeight()) - dp(20));
        if (side < dp(48)) return;
        float margin = dp(10);
        float left = x(drag[handle]) < getWidth() / 2f ? getWidth() - side - margin : margin;
        float top = y(drag[handle + 1]) < getHeight() / 2f ? getHeight() - side - margin : margin;
        loupeWindow.set(left, top, left + side, top + side);

        int pixelSide = Math.max(1, Math.min(Math.min(bitmap.getWidth(), bitmap.getHeight()),
                Math.round(side * bitmap.getWidth() / (image.width() * 2.8f))));
        float targetX = drag[handle] * bitmap.getWidth();
        float targetY = drag[handle + 1] * bitmap.getHeight();
        int pixelLeft = Math.max(0, Math.min(bitmap.getWidth() - pixelSide, Math.round(targetX - pixelSide / 2f)));
        int pixelTop = Math.max(0, Math.min(bitmap.getHeight() - pixelSide, Math.round(targetY - pixelSide / 2f)));
        loupeSource.set(pixelLeft, pixelTop, pixelLeft + pixelSide, pixelTop + pixelSide);
        loupeClip.reset();
        loupeClip.addRoundRect(loupeWindow, dp(10), dp(10), Path.Direction.CW);
        int saved = canvas.save();
        canvas.clipPath(loupeClip);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        canvas.drawBitmap(bitmap, loupeSource, loupeWindow, paint);
        float crossX = left + (targetX - pixelLeft) * side / pixelSide;
        float crossY = top + (targetY - pixelTop) * side / pixelSide;
        paint.setColor(0xFF122C29);
        paint.setStrokeWidth(dp(4));
        drawLoupeCross(canvas, crossX, crossY);
        paint.setColor(0xFF54F0D0);
        paint.setStrokeWidth(dp(2));
        drawLoupeCross(canvas, crossX, crossY);
        canvas.restoreToCount(saved);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0xFF132827);
        paint.setStrokeWidth(dp(5));
        canvas.drawPath(loupeClip, paint);
        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(dp(2));
        canvas.drawPath(loupeClip, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawLoupeCross(Canvas canvas, float x, float y) {
        canvas.drawLine(x - dp(9), y, x + dp(9), y, paint);
        canvas.drawLine(x, y - dp(9), x, y + dp(9), paint);
    }

    private void drawCrop(Canvas canvas, float[] corners, int index, boolean active) {
        int accent = active ? 0xFF29E3C3 : 0xFFFFFFFF;
        outline.reset();
        outline.moveTo(x(corners[0]), y(corners[1]));
        for (int i = 2; i < 8; i += 2) outline.lineTo(x(corners[i]), y(corners[i + 1]));
        outline.close();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(active ? 5 : 4));
        paint.setColor(0xC8000000);
        canvas.drawPath(outline, paint);
        paint.setStrokeWidth(dp(active ? 3 : 2));
        paint.setColor(accent);
        canvas.drawPath(outline, paint);
        paint.setStyle(Paint.Style.FILL);

        float centerX = (corners[0] + corners[2] + corners[4] + corners[6]) / 4f;
        float centerY = (corners[1] + corners[3] + corners[5] + corners[7]) / 4f;
        canvas.drawCircle(x(centerX), y(centerY), dp(13), paint);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(dp(14));
        paint.setFakeBoldText(true);
        paint.setColor(0xFF103B3B);
        canvas.drawText(Integer.toString(index + 1), x(centerX), y(centerY) + dp(5), paint);
        paint.setFakeBoldText(false);
        if (!active) return;
        for (int i = 0; i < 8; i += 2) {
            paint.setColor(0xFF103B3B);
            canvas.drawCircle(x(corners[i]), y(corners[i + 1]), dp(10), paint);
            paint.setColor(accent);
            canvas.drawCircle(x(corners[i]), y(corners[i + 1]), dp(7), paint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        updateImageBounds();
        if (!isEnabled() || bitmap == null || image.isEmpty()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchX = lastX = event.getX();
                touchY = lastY = event.getY();
                multiTouch = panning = false;
                pinchSpan = 0;
                drag = null;
                handle = -1;
                if (selected >= 0) {
                    float[] corners = crops.get(selected);
                    float best = dp(24);
                    for (int i = 0; i < 8; i += 2) {
                        float distance = (float) Math.hypot(touchX - x(corners[i]), touchY - y(corners[i + 1]));
                        if (distance < best) {
                            best = distance;
                            handle = i;
                        }
                    }
                    if (handle >= 0) {
                        drag = corners.clone();
                        invalidate();
                    }
                }
                if (handle >= 0 || zoom > 1f) disallowParentIntercept(true);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                multiTouch = true;
                drag = null;
                handle = -1;
                pinchSpan = 0;
                if (event.getPointerCount() == 2) updatePinch(event);
                disallowParentIntercept(true);
                invalidate();
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                // Wait for the next two-pointer sample before using a new pair.
                pinchSpan = 0;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (multiTouch) {
                    if (event.getPointerCount() == 2) updatePinch(event);
                } else if (drag != null) {
                    float[] corners = crops.get(selected);
                    drag[handle] = clamp(corners[handle] + (event.getX() - touchX) / image.width());
                    drag[handle + 1] = clamp(corners[handle + 1] + (event.getY() - touchY) / image.height());
                    invalidate();
                } else if (zoom > 1f && (panning || moved(event))) {
                    panX += event.getX() - lastX;
                    panY += event.getY() - lastY;
                    lastX = event.getX();
                    lastY = event.getY();
                    panning = true;
                    updateImageBounds();
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                float[] completed = drag;
                boolean edited = completed != null && !Arrays.equals(completed, crops.get(selected));
                drag = null;
                handle = -1;
                disallowParentIntercept(false);
                invalidate();
                if (edited) listener.move(completed);
                else if (completed == null && !multiTouch && !panning && !moved(event)) {
                    performClick();
                    float nx = (event.getX() - image.left) / image.width();
                    float ny = (event.getY() - image.top) / image.height();
                    for (int i = crops.size() - 1; i >= 0; i--) {
                        if (contains(crops.get(i), nx, ny)) {
                            if (i != selected) listener.select(i);
                            break;
                        }
                    }
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                drag = null;
                handle = -1;
                disallowParentIntercept(false);
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private void updatePinch(MotionEvent event) {
        float x = (event.getX(0) + event.getX(1)) / 2;
        float y = (event.getY(0) + event.getY(1)) / 2;
        float span = (float) Math.hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0));
        if (pinchSpan >= dp(4) && span >= dp(4)) {
            float nextZoom = Math.max(1, Math.min(MAX_ZOOM, zoom * span / pinchSpan));
            float factor = nextZoom / zoom;
            panX = x - getWidth() / 2f - (lastX - image.centerX()) * factor;
            panY = y - getHeight() / 2f - (lastY - image.centerY()) * factor;
            zoom = nextZoom;
            updateImageBounds();
            invalidate();
        }
        lastX = x;
        lastY = y;
        pinchSpan = span;
    }

    private boolean moved(MotionEvent event) {
        return Math.hypot(event.getX() - touchX, event.getY() - touchY) >= dp(14);
    }

    private void disallowParentIntercept(boolean disallow) {
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(disallow);
    }

    @Override public boolean performClick() { return super.performClick(); }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (!isEnabled() || selected < 0) return;
        addAction(info, CORNER_ACTION, "Select next crop corner");
        addAction(info, LEFT_ACTION, "Move corner left");
        addAction(info, RIGHT_ACTION, "Move corner right");
        addAction(info, UP_ACTION, "Move corner up");
        addAction(info, DOWN_ACTION, "Move corner down");
    }

    @Override public boolean performAccessibilityAction(int action, android.os.Bundle arguments) {
        if (!isEnabled() || selected < 0) return super.performAccessibilityAction(action, arguments);
        if (action == CORNER_ACTION) {
            accessibleCorner = (accessibleCorner + 1) % 4;
            show(bitmap, crops, selected);
            announceForAccessibility(CORNER_NAMES[accessibleCorner] + " corner");
            return true;
        }
        int coordinate = accessibleCorner * 2;
        float[] corners = crops.get(selected).clone();
        switch (action) {
            case LEFT_ACTION: corners[coordinate] = clamp(corners[coordinate] - 0.02f); break;
            case RIGHT_ACTION: corners[coordinate] = clamp(corners[coordinate] + 0.02f); break;
            case UP_ACTION: corners[coordinate + 1] = clamp(corners[coordinate + 1] - 0.02f); break;
            case DOWN_ACTION: corners[coordinate + 1] = clamp(corners[coordinate + 1] + 0.02f); break;
            default: return super.performAccessibilityAction(action, arguments);
        }
        listener.move(corners);
        return true;
    }

    private static void addAction(AccessibilityNodeInfo info, int id, String label) {
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(id, label));
    }

    private static boolean contains(float[] q, float x, float y) {
        boolean inside = false;
        for (int i = 0, j = 6; i < 8; j = i, i += 2) {
            if ((q[i + 1] > y) != (q[j + 1] > y)
                    && x < (q[j] - q[i]) * (y - q[i + 1]) / (q[j + 1] - q[i + 1]) + q[i]) inside = !inside;
        }
        return inside;
    }

    private float dp(float value) { return density * value; }
    private float x(float value) { return image.left + value * image.width(); }
    private float y(float value) { return image.top + value * image.height(); }
    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }
}
