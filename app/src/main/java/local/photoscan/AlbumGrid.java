package local.photoscan;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class AlbumGrid extends BaseAdapter {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> thumbnails = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private boolean thumbnailDemandDeferred;
    private final Map<String, ArrayList<WeakReference<TileView>>> pending = new HashMap<>();
    private final Set<String> unavailable = new HashSet<>();
    private List<ScanController.PhotoTile> photos = Collections.emptyList();
    private Set<String> selected = Collections.emptySet();
    private boolean selecting;
    private boolean disposed;

    AlbumGrid(Context context) { this.context = context; }

    void show(List<ScanController.PhotoTile> photos, Set<String> selected, boolean selecting) {
        if (this.photos == photos && this.selected.equals(selected) && this.selecting == selecting) return;
        this.photos = photos;
        this.selected = new HashSet<>(selected);
        this.selecting = selecting;
        notifyDataSetChanged();
    }

    @Override public int getCount() { return photos.size(); }
    @Override public ScanController.PhotoTile getItem(int index) { return photos.get(index); }
    @Override public long getItemId(int index) { return UUID.fromString(getItem(index).id).getMostSignificantBits(); }
    @Override public boolean hasStableIds() { return true; }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        TileView view = convertView instanceof TileView ? (TileView) convertView : new TileView();
        ScanController.PhotoTile photo = getItem(position);
        String label = "Page " + photo.page + " · Photo " + photo.number;
        boolean marked = selected.contains(photo.id);
        view.setContentDescription(label + (photo.needsCropReview ? ", Check crop" : "")
                + (selecting ? marked ? ", selected" : ", not selected" : ""));
        view.setBackground(background(marked ? 0xFFD5EEE4 : 0xFFFFFFFF, marked ? 0xFF00695D : 0xFFCBDCD7, 13));
        view.label.setText(label);
        // GridView positions the next row using one cell's height. Keep every tile the same height.
        view.review.setVisibility(photo.needsCropReview ? View.VISIBLE : View.INVISIBLE);
        view.mark.setText(marked ? "✓" : "○");
        view.mark.setVisibility(selecting ? View.VISIBLE : View.GONE);

        String key = photo.id + "/" + photo.turns + "/" + Arrays.toString(photo.corners);
        if (!key.equals(view.previewKey)) {
            view.previewKey = key;
            view.image.setImageDrawable(null);
        }
        Bitmap cached = thumbnails.get(key);
        if (cached != null) view.image.setImageBitmap(cached);
        else if (!disposed && !unavailable.contains(key)) requestThumbnail(photo, key, view);
        return view;
    }

    private void requestThumbnail(ScanController.PhotoTile photo, String key, TileView view) {
        ArrayList<WeakReference<TileView>> listeners = pending.get(key);
        if (listeners != null) {
            listeners.removeIf(reference -> reference.get() == null);
            for (WeakReference<TileView> listener : listeners) if (listener.get() == view) return;
            listeners.add(new WeakReference<>(view));
            return;
        }
        if (pending.size() >= 8) {
            thumbnailDemandDeferred = true;
            return;
        }
        listeners = new ArrayList<>();
        listeners.add(new WeakReference<>(view));
        pending.put(key, listeners);
        worker.execute(() -> load(photo, key));
    }

    private void load(ScanController.PhotoTile photo, String key) {
        Bitmap bitmap = null;
        try {
            bitmap = ImageEngine.render(photo.original, photo.corners, photo.turns, 0f, 240);
        } catch (Exception | OutOfMemoryError | LinkageError failure) {
            // The tile remains editable even if its thumbnail cannot be displayed.
        }
        Bitmap rendered = bitmap;
        main.post(() -> {
            if (disposed) {
                if (rendered != null) rendered.recycle();
                return;
            }
            ArrayList<WeakReference<TileView>> listeners = pending.remove(key);
            if (thumbnailDemandDeferred) {
                thumbnailDemandDeferred = false;
                notifyDataSetChanged();
            }
            if (rendered == null) {
                unavailable.add(key);
                return;
            }
            thumbnails.put(key, rendered);
            if (listeners != null) for (WeakReference<TileView> listener : listeners) {
                TileView view = listener.get();
                if (view != null && key.equals(view.previewKey)) view.image.setImageBitmap(rendered);
            }
        });
    }

    void dispose() {
        disposed = true;
        pending.clear();
        thumbnails.evictAll();
        worker.shutdownNow();
    }

    private final class TileView extends LinearLayout {
        final ImageView image;
        final TextView label;
        final TextView mark;
        final TextView review;
        String previewKey = "";

        TileView() {
            super(context);
            setOrientation(VERTICAL);
            setPadding(dp(6), dp(6), dp(6), dp(7));
            FrameLayout frame = new FrameLayout(context);
            frame.setBackground(background(0xFF102725, 0xFF102725, 9));
            image = new ImageView(context);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
            mark = new TextView(context);
            mark.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 17);
            mark.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            mark.setTextColor(0xFFFFFFFF);
            mark.setGravity(Gravity.CENTER);
            mark.setBackground(background(0xFF00695D, 0xFF00695D, 16));
            mark.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams markParams = new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP | Gravity.END);
            markParams.setMargins(dp(4), dp(4), dp(4), dp(4));
            frame.addView(mark, markParams);
            var configuration = getResources().getConfiguration();
            boolean shortWindow = configuration.screenHeightDp < 480;
            int imageHeight = shortWindow ? 78 : 122;
            addView(frame, new LayoutParams(-1, dp(imageHeight)));
            label = new TextView(context);
            label.setTextSize(13);
            label.setLines(!shortWindow && configuration.fontScale >= 1.3f ? 2 : 1);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            label.setTextColor(0xFF143532);
            label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            label.setPadding(dp(3), dp(5), dp(2), 0);
            label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(label);
            review = new TextView(context);
            review.setText("Check crop");
            review.setTextSize(12);
            review.setSingleLine(true);
            review.setEllipsize(android.text.TextUtils.TruncateAt.END);
            review.setTextColor(0xFF6B4100);
            review.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            review.setPadding(dp(5), dp(2), dp(5), dp(2));
            review.setBackground(background(0xFFFFE8B5, 0xFFFFE8B5, 4));
            review.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(review);
        }
    }

    private GradientDrawable background(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}