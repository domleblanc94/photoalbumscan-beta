package local.photoscan;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;

final class CameraFrameGeometry {
    private CameraFrameGeometry() {}

    static Matrix bufferToUpright(Rect crop, int clockwiseDegrees) {
        Matrix transform = new Matrix();
        transform.setRotate(clockwiseDegrees);
        RectF rotated = new RectF(crop);
        transform.mapRect(rotated);
        transform.postTranslate(-rotated.left, -rotated.top);
        return transform;
    }
}