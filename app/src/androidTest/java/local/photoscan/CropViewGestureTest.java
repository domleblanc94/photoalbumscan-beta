package local.photoscan;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.view.InputDevice;
import android.view.MotionEvent;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class CropViewGestureTest {
    @Test public void handleDragKeepsFingerOffsetAndTapDoesNotEdit() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            var editor = new Editor();
            float corner = editor.fitCorner();
            editor.touch(MotionEvent.ACTION_DOWN, corner + 6, corner + 7);
            editor.touch(MotionEvent.ACTION_MOVE, corner + 6, corner + 7);
            editor.touch(MotionEvent.ACTION_UP, corner + 6, corner + 7);
            assertTrue(editor.edits.isEmpty());
            editor.drag(corner + 6, corner + 7, .05f * editor.fitWidth(), .03f * editor.fitWidth());
            editor.assertEdit(.30f, .28f);
        });
    }

    @Test public void pinchCancelsPendingEditAndPanAndFitOnlyMoveViewport() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            var editor = new Editor();
            float center = editor.size / 2f;
            float corner = editor.fitCorner();
            editor.touch(MotionEvent.ACTION_DOWN, corner, corner);
            editor.touch(MotionEvent.ACTION_MOVE, corner + 12, corner + 8);
            editor.touch(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    corner, corner, editor.size - corner, editor.size - corner);
            float inset = 10 * editor.density;
            editor.touch(MotionEvent.ACTION_MOVE, inset, inset, editor.size - inset, editor.size - inset);
            editor.touch(MotionEvent.ACTION_POINTER_UP, inset, inset, editor.size - inset, editor.size - inset);
            editor.touch(MotionEvent.ACTION_MOVE, center + 30, center + 20);
            editor.touch(MotionEvent.ACTION_UP, center + 30, center + 20);
            assertTrue(editor.edits.isEmpty());
            assertTrue(editor.selections.isEmpty());

            float panX = 45 * editor.density;
            float panY = 25 * editor.density;
            editor.drag(center, center, panX, panY);
            assertTrue(editor.edits.isEmpty());
            editor.view.show(editor.bitmap, List.of(editor.corners), 0);
            editor.drag(inset + panX + 6, inset + panY + 7, .10f * editor.fitWidth(), 0);
            editor.assertEdit(.30f, .25f);

            editor.edits.clear();
            editor.view.fitPage();
            assertTrue(editor.edits.isEmpty());
            editor.drag(corner + 6, corner + 7, .05f * editor.fitWidth(), 0);
            editor.assertEdit(.30f, .25f);
        });
    }

    private static final class Editor {
        final float[] corners = {.25f, .25f, .75f, .25f, .75f, .75f, .25f, .75f};
        final Bitmap bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
        final List<float[]> edits = new ArrayList<>();
        final List<Integer> selections = new ArrayList<>();
        final CropView view;
        final float density;
        final int size;
        private long eventTime;
        private long downTime;
        private int singlePointerId;

        Editor() {
            var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            density = context.getResources().getDisplayMetrics().density;
            size = Math.round(400 * density);
            view = new CropView(context, new CropView.Listener() {
                @Override public void select(int crop) { selections.add(crop); }
                @Override public void move(float[] corners) { edits.add(corners); }
            });
            view.layout(0, 0, size, size);
            view.show(bitmap, List.of(corners), 0);
        }

        float fitWidth() { return size - 20 * density; }
        float fitCorner() { return 10 * density + .25f * fitWidth(); }

        void drag(float x, float y, float dx, float dy) {
            touch(MotionEvent.ACTION_DOWN, x, y);
            touch(MotionEvent.ACTION_MOVE, x + dx, y + dy);
            touch(MotionEvent.ACTION_UP, x + dx, y + dy);
        }

        void assertEdit(float x, float y) {
            assertEquals(1, edits.size());
            float[] expected = corners.clone();
            expected[0] = x;
            expected[1] = y;
            assertArrayEquals(expected, edits.get(0), .0001f);
        }

        void touch(int action, float... points) {
            eventTime += 16;
            int masked = action & MotionEvent.ACTION_MASK;
            if (masked == MotionEvent.ACTION_DOWN) {
                downTime = eventTime;
                singlePointerId = 0;
            }
            int count = points.length / 2;
            var properties = new MotionEvent.PointerProperties[count];
            var coordinates = new MotionEvent.PointerCoords[count];
            for (int i = 0; i < count; i++) {
                properties[i] = new MotionEvent.PointerProperties();
                properties[i].id = count == 1 ? singlePointerId : i;
                properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
                coordinates[i] = new MotionEvent.PointerCoords();
                coordinates[i].x = points[2 * i];
                coordinates[i].y = points[2 * i + 1];
            }
            var event = MotionEvent.obtain(downTime, eventTime, action, count, properties, coordinates,
                    0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            try {
                assertTrue(view.dispatchTouchEvent(event));
            } finally {
                event.recycle();
            }
            if (masked == MotionEvent.ACTION_POINTER_UP) {
                singlePointerId = 1 - (action >> MotionEvent.ACTION_POINTER_INDEX_SHIFT);
            }
        }
    }
}
