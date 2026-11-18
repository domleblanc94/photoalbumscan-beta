package local.photoscan;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

final class BatchStrip extends HorizontalScrollView {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LruCache<File, Bitmap> thumbnails = new LruCache<File, Bitmap>(3 * 1024 * 1024) {
        @Override protected int sizeOf(File file, Bitmap thumbnail) { return thumbnail.getAllocationByteCount(); }
    };
    private final Set<File> loading = new HashSet<>();
    private final Set<File> unreadable = new HashSet<>();
    private final ArrayList<FrameLayout> tiles = new ArrayList<>();
    private final ArrayList<ImageView> images = new ArrayList<>();
    private final LinearLayout row;
    private final IntConsumer selectPage;
    private List<File> files = Collections.emptyList();
    private int selected = -1;
    private boolean interactive;
    private boolean disposed;

    BatchStrip(Context context, IntConsumer selectPage) {
        super(context);
        this.selectPage = selectPage;
        setHorizontalScrollBarEnabled(false);
        setClipToPadding(false);
        setContentDescription("Batch page thumbnails");
        row = new LinearLayout(context);
        row.setPadding(0, dp(5), dp(3), dp(3));
        addView(row, new FrameLayout.LayoutParams(-2, -2));
    }

    void showPages(List<File> pages, int selectedPage) {
        boolean rebuilt = !files.equals(pages);
        boolean changedSelection = selected != selectedPage;
        if (rebuilt) {
            files = new ArrayList<>(pages);
            row.removeAllViews();
            tiles.clear();
            images.clear();
            unreadable.retainAll(files);
            for (int page = 0; page < files.size(); page++) addPage(page);
        }
        selected = selectedPage;
        for (int page = 0; page < tiles.size(); page++) {
            FrameLayout tile = tiles.get(page);
            tile.setSelected(page == selected);
            tile.setBackground(border(page == selected ? 0xFF00695D : 0xFFDBEAE6));
        }
        post(() -> {
            if (rebuilt || changedSelection) ensureSelectionVisible();
            loadNearby();
        });
    }

    void setInteractive(boolean enabled) {
        interactive = enabled;
        for (FrameLayout tile : tiles) tile.setEnabled(enabled);
    }

    private void addPage(int page) {
        FrameLayout tile = new FrameLayout(getContext());
        tile.setPadding(dp(3), dp(3), dp(3), dp(3));
        tile.setFocusable(true);
        tile.setContentDescription("Go to page " + (page + 1));
        tile.setOnClickListener(view -> selectPage.accept(page));
        tile.setEnabled(interactive);

        ImageView image = new ImageView(getContext());
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.addView(image, new FrameLayout.LayoutParams(-1, -1));
        TextView number = new TextView(getContext());
        number.setText(Integer.toString(page + 1));
        number.setGravity(Gravity.CENTER);
        number.setTextColor(Color.WHITE);
        number.setTextSize(12);
        number.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        number.setMinWidth(dp(22));
        number.setBackground(border(0xE9153531));
        number.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(-2, dp(20), Gravity.BOTTOM | Gravity.END);
        labelParams.setMargins(dp(2), dp(2), dp(2), dp(2));
        tile.addView(number, labelParams);

        LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(dp(62), dp(56));
        tileParams.rightMargin = dp(6);
        row.addView(tile, tileParams);
        tiles.add(tile);
        images.add(image);
    }

    private void ensureSelectionVisible() {
        if (disposed || selected < 0 || selected >= tiles.size()) return;
        View tile = tiles.get(selected);
        if (tile.getLeft() < getScrollX() || tile.getRight() > getScrollX() + getWidth()) {
            scrollTo(Math.max(0, tile.getLeft() - dp(4)), 0);
        }
    }

    @Override protected void onScrollChanged(int left, int top, int oldLeft, int oldTop) {
        super.onScrollChanged(left, top, oldLeft, oldTop);
        loadNearby();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        post(this::loadNearby);
    }

    private boolean nearby(View tile) {
        int margin = getWidth() / 2;
        return tile.getRight() > getScrollX() - margin && tile.getLeft() < getScrollX() + getWidth() + margin;
    }

    private void loadNearby() {
        if (disposed || getWidth() == 0 || getVisibility() != VISIBLE || !isShown()) return;
        for (int page = 0; page < tiles.size(); page++) {
            if (!nearby(tiles.get(page))) {
                images.get(page).setImageDrawable(null);
                images.get(page).setTag(null);
                continue;
            }
            File file = files.get(page);
            Bitmap cached = thumbnails.get(file);
            if (cached != null) {
                if (images.get(page).getTag() != cached || images.get(page).getDrawable() == null) {
                    images.get(page).setImageBitmap(cached);
                    images.get(page).setTag(cached);
                }
            } else if (!unreadable.contains(file) && loading.size() < 8 && loading.add(file)) {
                worker.execute(() -> load(file));
            }
        }
    }

    private void load(File file) {
        Bitmap bitmap = null;
        try {
            bitmap = ImageEngine.preview(file, 120);
        } catch (Exception | OutOfMemoryError unavailable) {
            // A failed page thumbnail still has its page number and remains selectable.
        }
        Bitmap result = bitmap;
        post(() -> {
            loading.remove(file);
            if (disposed || !files.contains(file)) {
                if (result != null) result.recycle();
                return;
            }
            if (result == null) unreadable.add(file);
            else thumbnails.put(file, result);
            loadNearby();
        });
    }

    void dispose() {
        disposed = true;
        worker.shutdownNow();
        thumbnails.evictAll();
    }

    private GradientDrawable border(int color) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(7));
        return background;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
