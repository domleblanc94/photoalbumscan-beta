package local.photoscan;

import ai.onnxruntime.OrtException;
import android.content.Context;
import android.graphics.Bitmap;
import android.util.Size;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Optional saved-page locator using the app's f083 print model. */
final class OwnPictureLocator implements AutoCloseable {
    private final QuadModel model;
    private final boolean supplement;

    OwnPictureLocator(Context context, boolean supplement) throws IOException, OrtException {
        model = QuadModel.loadSavedLocator(context);
        this.supplement = supplement;
    }

    PicturePageDecode.Page detect(File original, Size source) throws IOException, OrtException {
        try (Input input=open(original,source)) {
            return input.page;
        }
    }

    static final class Input implements AutoCloseable {
        final Bitmap preview;
        final PicturePageDecode.Page page;
        Input(Bitmap preview,PicturePageDecode.Page page) {
            this.preview=preview;
            this.page=page;
        }
        @Override public void close() {preview.recycle();}
    }

    Input open(File original,Size source) throws IOException,OrtException {
        Bitmap preview=ImageEngine.preview(original,1000);
        try {
            return new Input(preview,proposals(model.detect(preview,supplement),source));
        } catch (OrtException | RuntimeException | LinkageError | OutOfMemoryError error) {
            preview.recycle();
            throw error;
        }
    }

    LocalPictureLocator.Evidence local(Input input,Size source,java.util.Set<Integer> poor,
                                      java.util.Set<Integer> undercovered)
            throws OrtException,java.io.InterruptedIOException {
        return LocalPictureLocator.detect(model,input.preview,source,input.page.proposals(),poor,undercovered);
    }

    static PicturePageDecode.Page proposals(List<QuadModel.Prediction> predictions, Size source) {
        List<PicturePageDecode.Proposal> proposals = new ArrayList<>(predictions.size());
        for (var prediction : predictions) {
            float[] edges = prediction.quad();
            var corners = new PictureQuadFit.Vertex[4];
            float cx = 0, cy = 0;
            for (int i = 0; i < corners.length; i++) {
                // f083 emits Canvas edge fractions. The ROI sampler uses upright pixel centers.
                float x = edges[2 * i] * source.getWidth() - .5f;
                float y = edges[2 * i + 1] * source.getHeight() - .5f;
                corners[i] = new PictureQuadFit.Vertex(x, y);
                cx += x;
                cy += y;
            }
            proposals.add(new PicturePageDecode.Proposal(corners, cx / 4, cy / 4, ""));
        }
        return new PicturePageDecode.Page(proposals, proposals.size() > ScanSession.MAX_CROPS);
    }

    @Override public void close() throws OrtException {
        model.close();
    }
}
