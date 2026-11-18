package local.photoscan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ScanActivity extends Activity {
    private static final int CAMERA = 31;
    private static final int GALLERY = 32;
    private static final int PAPER = 0xFFF4F8F7;
    private static final int INK = 0xFF143532;
    private static final int MUTED = 0xFF4D6460;
    private static final int TEAL = 0xFF00695D;
    private static final int SOFT = 0xFFE3F0EC;
    private static final int STROKE = 0xFFCBDCD7;
    private static final int WARNING = 0xFF9A3324;

    private ScanController controller;
    private ScanController.Screen screen;
    private int external;
    private boolean galleryFromCamera;
    private boolean selecting;
    private boolean reviewOnly;
    private boolean compactGallery;
    private String displayedAlbum;
    private Uri savedPhotoUri;
    private LinearLayout masthead, library, libraryEntries, galleryBottom;
    private List<ScanController.AlbumEntry> listedAlbums = List.of();
    private Button newAlbum, libraryDeveloper, galleryBack, undoRemove, exportMenu, markChecked, galleryMenu, albumImport;
    private boolean albumInteractive;
    private boolean adjustingCorners;
    private Parcelable galleryPosition;
    private final LinkedHashSet<String> selectedPhotos = new LinkedHashSet<>();

    private TextView mode, status, pageTitle, cropTitle, canvasHint, photoNotes, galleryEmpty, libraryEmpty,
            originalsEmpty,
            galleryTitle, gallerySummary;
    private LinearLayout review, editorFooter, cropDetail, recovery, album,
            pageEditor, photoEditor, pageNavigation, cropNavigation;
    private ScrollView scroll;
    private ProgressBar progress;
    private Button prevPage, nextPage, retake, deleteOriginal, prevCrop, nextCrop, add, remove, rotate,
            back, recover, discard, editNotes, adjustCorners, albumScan,
            showPhoto;
    private CropView cropView;
    private ImageView corrected;
    private BatchStrip batchStrip;
    private GridView grid;
    private AlbumGrid albumGrid;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        external = saved == null ? 0 : saved.getInt("external");
        galleryFromCamera = saved != null && saved.getBoolean("galleryFromCamera");
        if (saved != null) {
            galleryPosition = saved.getParcelable("galleryPosition");
            adjustingCorners = saved.getBoolean("adjustingCorners");
            selecting = saved.getBoolean("selecting");
            reviewOnly = saved.getBoolean("reviewOnly");
            displayedAlbum = saved.getString("displayedAlbum");
            savedPhotoUri = saved.getParcelable("savedPhotoUri");
            ArrayList<String> restored = saved.getStringArrayList("selectedPhotos");
            if (restored != null) selectedPhotos.addAll(restored);
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(true);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightNavigationBars(true);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        controller = ScanController.get(this);
        makeLayout();
        controller.attach(this);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("external", external);
        state.putBoolean("galleryFromCamera", galleryFromCamera);
        state.putBoolean("adjustingCorners", adjustingCorners);
        state.putParcelable("galleryPosition", album.isShown() ? grid.onSaveInstanceState() : galleryPosition);
        state.putBoolean("selecting", selecting);
        state.putBoolean("reviewOnly", reviewOnly);
        state.putString("displayedAlbum", displayedAlbum);
        state.putParcelable("savedPhotoUri", savedPhotoUri);
        state.putStringArrayList("selectedPhotos", new ArrayList<>(selectedPhotos));
    }

    @Override protected void onDestroy() {
        batchStrip.dispose();
        albumGrid.dispose();
        controller.detach(this);
        super.onDestroy();
    }

    private void makeLayout() {
        compactGallery = getResources().getConfiguration().screenHeightDp < 480
                || getResources().getConfiguration().fontScale >= 1.3f;
        LinearLayout root = column();
        root.setBackgroundColor(PAPER);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });

        masthead = column();
        masthead.setPadding(dp(20), dp(12), dp(20), dp(10));
        TextView name = label(getString(R.string.app_name), 28, INK, true);
        name.setAccessibilityHeading(true);
        masthead.addView(name);
        mode = label("Albums", 22, INK, true);
        mode.setAccessibilityHeading(true);
        masthead.addView(mode);
        if (compactGallery) {
            name.setVisibility(View.GONE);
            masthead.setPadding(dp(16), dp(3), dp(16), dp(3));
        }
        root.addView(masthead);

        status = label("", 14, INK, false);
        status.setBackground(rounded(SOFT, 12, Color.TRANSPARENT));
        status.setPadding(dp(14), dp(10), dp(14), dp(10));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.setMargins(dp(16), 0, dp(16), dp(7));
        root.addView(status, statusParams);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(TEAL));
        progress.setContentDescription("Processing photos");
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));

        recovery = card();
        LinearLayout.LayoutParams recoveryParams = new LinearLayout.LayoutParams(-1, -2);
        recoveryParams.setMargins(dp(16), 0, dp(16), dp(8));
        root.addView(recovery, recoveryParams);
        recover = button(recovery, "Recover camera photo", false, () -> controller.finishCamera(true));
        discard = button(recovery, "Discard camera shot", false, () -> controller.finishCamera(false));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout body = column();
        body.setPadding(dp(16), dp(10), dp(16), dp(12));
        scroll.addView(body);

        library = column();
        body.addView(library);
        newAlbum = button(library, "New album", true, this::nameNewAlbum);
        libraryEmpty = label("Create an album to start scanning or importing photos.", 15, MUTED, false);
        libraryEmpty.setPadding(0, dp(8), 0, 0);
        library.addView(libraryEmpty);
        libraryEntries = column();
        library.addView(libraryEntries);
        libraryDeveloper = button(library, "More", false, () -> new AlertDialog.Builder(this)
                .setTitle("More").setItems(new String[]{"New training collection",
                        "Experimental crop refinement: "+(controller.instanceBoundaryEnabled()?"On":"Off"), "Open-source licences"},
                        (dialog, which) -> { if(which==0)nameNewCollection();else if(which==1)toggleInstanceBoundary();else showOpenSourceLicenses(); })
                .setNegativeButton("Cancel", null).show());

        review = column();
        body.addView(review);
        originalsEmpty = label("No original scans remain.", 15, MUTED, false);
        originalsEmpty.setPadding(0, dp(8), 0, 0);
        originalsEmpty.setVisibility(View.GONE);
        review.addView(originalsEmpty);
        LinearLayout editor = pageEditor = card();
        review.addView(editor);
        pageTitle = label("Page", 18, INK, true);
        pageTitle.setAccessibilityHeading(true);
        editor.addView(pageTitle);
        batchStrip = new BatchStrip(this, controller::selectPage);
        editor.addView(batchStrip, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout pages = pageNavigation = row(editor);
        prevPage = button(pages, "Previous page", false, () -> controller.selectPage(screen.page - 1));
        nextPage = button(pages, "Next page", false, () -> controller.selectPage(screen.page + 1));

        FrameLayout canvas = new FrameLayout(this);
        canvas.setBackground(rounded(0xFF132827, 12, Color.TRANSPARENT));
        cropView = new CropView(this, new CropView.Listener() {
            @Override public void select(int crop) {
                if (screen != null && screen.originalsFirst) controller.selectCrop(crop);
            }
            @Override public void move(float[] corners) { controller.moveCorners(corners); }
        });
        canvas.addView(cropView, new FrameLayout.LayoutParams(-1, -1));
        int canvasHeight = Math.min(420, Math.max(180, getResources().getConfiguration().screenHeightDp * 3 / 5));
        editor.addView(canvas, new LinearLayout.LayoutParams(-1, dp(canvasHeight)));
        canvasHint = label("Select a crop and drag its corners to fit the print.", 13, MUTED, false);
        canvasHint.setPadding(0, dp(7), 0, dp(4));
        editor.addView(canvasHint);
        button(editor, "Fit page", false, cropView::fitPage);
        LinearLayout originalActions = row(editor);
        retake = button(originalActions, "Retake page", false, this::confirmRetake);
        deleteOriginal = button(originalActions, "Delete original scan", false, this::confirmDeleteOriginal);
        LinearLayout changes = row(editor);
        add = button(changes, "Add crop", false, controller::addCrop);
        remove = button(changes, "Remove crop", false, controller::removeCrop);
        showPhoto = button(editor, "Back to photo", false, () -> setAdjustingCorners(false));

        LinearLayout proof = photoEditor = card();
        addCard(review, proof);
        cropTitle = label("Corrected preview", 18, INK, true);
        cropTitle.setAccessibilityHeading(true);
        proof.addView(cropTitle);
        LinearLayout crops = cropNavigation = row(proof);
        prevCrop = button(crops, "Previous crop", false, () -> controller.selectCrop(screen.selection - 1));
        nextCrop = button(crops, "Next crop", false, () -> controller.selectCrop(screen.selection + 1));

        cropDetail = column();
        proof.addView(cropDetail);
        corrected = new ImageView(this);
        corrected.setScaleType(ImageView.ScaleType.FIT_CENTER);
        corrected.setBackground(rounded(0xFF132827, 12, Color.TRANSPARENT));
        corrected.setContentDescription("Perspective-corrected photo preview");
        cropDetail.addView(corrected, new LinearLayout.LayoutParams(-1, dp(160)));
        rotate = button(cropDetail, "Rotate 90°", false, controller::rotate);
        rotate.setContentDescription("Rotate photo 90 degrees clockwise");
        photoNotes = label("", 14, MUTED, false);
        photoNotes.setMaxLines(2);
        photoNotes.setEllipsize(TextUtils.TruncateAt.END);
        photoNotes.setPadding(0, dp(8), 0, 0);
        cropDetail.addView(photoNotes);
        markChecked = button(cropDetail, "Mark crop checked", false, controller::markCropChecked);
        editNotes = button(cropDetail, "Edit date & notes", false, this::editPhotoNotes);
        adjustCorners = button(cropDetail, "Adjust corners", false, () -> setAdjustingCorners(true));

        editorFooter = column();
        editorFooter.setPadding(dp(16), dp(2), dp(16), dp(8));
        editorFooter.setBackgroundColor(Color.WHITE);
        back = button(editorFooter, "Done", true, controller::openAlbum);
        root.addView(editorFooter);
        makeAlbum(root);
        setContentView(root);
    }

    private void makeAlbum(LinearLayout root) {
        album = column();
        album.setPadding(dp(16), dp(8), dp(16), dp(8));
        root.addView(album, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout header = row(album);
        header.setGravity(Gravity.CENTER_VERTICAL);
        galleryBack = button(header, "‹ Albums", false, this::goBackFromGallery);
        galleryBack.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        galleryBack.setMinHeight(dp(48));
        galleryBack.setMinWidth(dp(48));
        galleryBack.setPadding(dp(10), 0, dp(10), 0);
        galleryBack.setContentDescription("Albums");
        flattenGalleryButton(galleryBack);
        galleryTitle = label("Album", 20, INK, true);
        galleryTitle.setAccessibilityHeading(true);
        galleryTitle.setSingleLine(true);
        galleryTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1);
        titleParams.setMarginStart(dp(8));
        titleParams.setMarginEnd(dp(8));
        header.addView(galleryTitle, titleParams);
        galleryMenu = galleryIcon(header, "⋮", "Album actions", this::openGalleryActions);
        gallerySummary = label("", 14, MUTED, false);
        gallerySummary.setMaxLines(2);
        gallerySummary.setEllipsize(TextUtils.TruncateAt.END);
        album.addView(gallerySummary);

        FrameLayout gallery = new FrameLayout(this);
        LinearLayout.LayoutParams galleryParams = new LinearLayout.LayoutParams(-1, 0, 1);
        galleryParams.topMargin = dp(compactGallery ? 4 : 10);
        album.addView(gallery, galleryParams);
        grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        var configuration = getResources().getConfiguration();
        grid.setColumnWidth(dp(configuration.screenHeightDp < 480 && configuration.fontScale >= 1.3f ? 168 : 128));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(8));
        grid.setVerticalSpacing(dp(8));
        grid.setClipToPadding(false);
        grid.setPadding(0, 0, 0, dp(8));
        grid.setContentDescription("Scanned photos");
        gallery.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        galleryEmpty = label("No photos yet. Open original scans to add a missed crop.", 15, MUTED, false);
        galleryEmpty.setGravity(Gravity.CENTER);
        galleryEmpty.setPadding(dp(20), 0, dp(20), 0);
        gallery.addView(galleryEmpty, new FrameLayout.LayoutParams(-1, -1));
        albumGrid = new AlbumGrid(this);
        grid.setAdapter(albumGrid);
        grid.setEmptyView(galleryEmpty);
        grid.setOnItemClickListener((parent, view, position, id) -> choosePhoto(albumGrid.getItem(position).id));
        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            if (!albumInteractive) return false;
            selecting = true;
            selectedPhotos.add(albumGrid.getItem(position).id);
            updateAlbumControls(screen);
            return true;
        });

        galleryBottom = row(album);
        albumScan = button(galleryBottom, "Scan pages", true, () -> openCamera(false));
        albumImport = button(galleryBottom, "Import photos", false, () -> {
            if (screen != null && screen.collection) controller.editOriginals();
            else openGallery();
        });
        exportMenu = button(galleryBottom, "Export", false, this::openExportActions);
        undoRemove = button(galleryBottom, "Undo", false, controller::undoRemovePhotos);
        undoRemove.setContentDescription("Undo removal");
        for (Button action : new Button[]{albumScan, albumImport, exportMenu, undoRemove}) flattenGalleryButton(action);
    }

    private Button galleryIcon(LinearLayout header, String symbol, String description, Runnable action) {
        Button button = button(header, symbol, false, action);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        button.setMinimumWidth(dp(48));
        button.setMinWidth(dp(48));
        button.setPadding(0, 0, 0, 0);
        button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 24);
        button.setIncludeFontPadding(false);
        button.setContentDescription(description);
        button.setTooltipText(description);
        flattenGalleryButton(button);
        return button;
    }

    private void flattenGalleryButton(Button button) {
        button.setStateListAnimator(null);
        button.setElevation(0);
    }

    private void goBackFromGallery() {
        if (selecting) toggleSelection();
        else controller.showLibrary();
    }

    private void updateAlbumControls(ScanController.Screen next) {
        if (next == null || !next.review || next.editing) return;
        List<ScanController.PhotoTile> shown = shownPhotos(next);
        if (albumInteractive && !selectedPhotos.isEmpty()) {
            LinkedHashSet<String> available = new LinkedHashSet<>();
            for (ScanController.PhotoTile photo : shown) available.add(photo.id);
            selectedPhotos.retainAll(available);
        }
        int photos = shown.size();
        galleryTitle.setText(selecting ? selectedPhotos.size() + " selected" : next.album);
        galleryBack.setText(selecting ? "×" : "‹ Albums");
        String backDescription = selecting ? "Cancel selection" : "Albums";
        galleryBack.setContentDescription(backDescription);
        galleryBack.setTooltipText(backDescription);
        galleryBack.setEnabled(albumInteractive);
        String menuDescription = selecting ? "Selected actions" : "Album actions";
        galleryMenu.setContentDescription(menuDescription);
        galleryMenu.setTooltipText(menuDescription);
        galleryMenu.setEnabled(albumInteractive);
        if (next.collection) {
            gallerySummary.setText(next.pageCount + (next.pageCount == 1 ? " original" : " originals") + " · "
                    + next.physicalBooks.size() + (next.physicalBooks.size() == 1 ? " physical album" : " physical albums"));
            galleryEmpty.setText("Training collection · Full-resolution originals");
            albumScan.setText("Collect originals");
            albumScan.setVisibility(View.VISIBLE);
            albumScan.setEnabled(albumInteractive);
            albumImport.setText("Review originals");
            albumImport.setVisibility(next.pageCount == 0 ? View.GONE : View.VISIBLE);
            albumImport.setEnabled(albumInteractive);
            undoRemove.setVisibility(View.GONE);
            exportMenu.setVisibility(View.GONE);
            galleryBottom.setVisibility(View.VISIBLE);
            albumGrid.show(List.of(), Set.of(), false);
            return;
        }
        albumScan.setText("Scan pages");
        albumImport.setText("Import photos");
        String summary = photos + (photos == 1 ? " photo" : " photos") + " · " + next.redetectablePages
                + (next.redetectablePages == 1 ? " scan" : " scans");
        if (next.retainedPageCount > 0) summary += " · " + next.retainedPageCount
                + (next.retainedPageCount == 1 ? " retained photo" : " retained photos");
        if (reviewOnly) summary += " · Check crop only";
        if (next.pagesWithoutCrops > 0) summary += " · " + next.pagesWithoutCrops
                + (next.pagesWithoutCrops == 1 ? " scan with no crops" : " scans with no crops");
        if (next.pendingPages > 0) summary += " · " + next.pendingPages + " processing";
        if (next.failedPages > 0) summary += " · " + next.failedPages + " failed";
        if (next.reviewPages > 0) summary += " · " + next.reviewPages + " to check";
        if (!selecting && !next.albumDate.isEmpty()) {
            summary += (compactGallery ? " · Album date " : "\nAlbum date · ") + next.albumDate;
        }
        gallerySummary.setText(summary);
        galleryEmpty.setText(reviewOnly ? "No photos need a crop check." : next.pendingPages > 0 ? "Finding photos. You can keep scanning."
                : next.pageCount == 0 ? "Scan album pages or import photos to get started."
                : "No photos yet. Open original scans to add a missed crop.");
        grid.setEnabled(albumInteractive);
        albumScan.setVisibility(selecting ? View.GONE : View.VISIBLE);
        albumScan.setEnabled(albumInteractive);
        albumImport.setVisibility(!selecting && next.pageCount == 0 ? View.VISIBLE : View.GONE);
        albumImport.setEnabled(albumInteractive);
        undoRemove.setVisibility(!selecting && next.undoCount > 0 ? View.VISIBLE : View.GONE);
        undoRemove.setEnabled(albumInteractive);
        exportMenu.setVisibility(next.pageCount > 0 || savedPhotoUri != null ? View.VISIBLE : View.GONE);
        exportMenu.setEnabled(albumInteractive && (photos > 0 || savedPhotoUri != null));
        galleryBottom.setVisibility(selecting ? View.GONE : View.VISIBLE);
        albumGrid.show(shown, selectedPhotos, selecting);
    }

    private List<ScanController.PhotoTile> shownPhotos(ScanController.Screen next) {
        if (!reviewOnly) return next.photos;
        ArrayList<ScanController.PhotoTile> flagged = new ArrayList<>();
        for (ScanController.PhotoTile photo : next.photos) if (photo.needsCropReview) flagged.add(photo);
        return flagged;
    }

    private void openGalleryActions() {
        if (screen == null || !albumInteractive) return;
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, galleryMenu, Gravity.END);
        int visibleCount = shownPhotos(screen).size();
        if (screen.collection) {
            addGalleryAction(menu, "Review originals", screen.pageCount > 0, controller::editOriginals);
            addGalleryAction(menu, "Export collection ZIP", screen.pageCount > 0, this::confirmReviewArchive);
            addGalleryAction(menu, "Edit collection name", true, this::editAlbum);
            addGalleryAction(menu, "Delete album", true, this::confirmDeleteAlbum);
        } else if (selecting) {
            boolean selected = !selectedPhotos.isEmpty();
            addGalleryAction(menu, selectedPhotos.size() == visibleCount ? "Clear selection" : "Select all",
                    visibleCount > 0, this::toggleSelectAll);
            addGalleryAction(menu, "Export JPEGs", selected, () -> controller.export(selectedPhotos, false));
            addGalleryAction(menu, "Export & share", selected, () -> controller.export(selectedPhotos, true));
            addGalleryAction(menu, "Set date", selected, this::editSelectedDate);
            addGalleryAction(menu, "Rotate 90°", selected, () -> controller.rotateSelectedPhotos(selectedPhotos));
            addGalleryAction(menu, "Remove photos", selected, this::removeSelectedPhotos);
            if (savedPhotoUri != null) addGalleryAction(menu, "Open saved photo", true, this::openSavedPhoto);
        } else {
            if (screen.pageCount > 0) addGalleryAction(menu, "Import photos", true, this::openGallery);
            addGalleryAction(menu, "Edit original scans", screen.pageCount > 0, controller::editOriginals);
            addGalleryAction(menu, "Select photos", visibleCount > 0, this::toggleSelection);
            long flagged = screen.photos.stream().filter(photo -> photo.needsCropReview).count();
            addGalleryAction(menu, reviewOnly ? "Show all photos" : "Show only Check crop (" + flagged + ")", true, () -> {
                reviewOnly = !reviewOnly;
                updateAlbumControls(screen);
            });
            if (screen.failedPages > 0) addGalleryAction(menu, "Retry failed scans", true, controller::retryFailedPages);
            if (savedPhotoUri != null) addGalleryAction(menu, "Open saved photo", true, this::openSavedPhoto);
            addGalleryAction(menu, "Album settings", true, this::openAlbumSettings);
            addGalleryAction(menu, "Delete album", true, this::confirmDeleteAlbum);
            var developerTools = menu.getMenu().addSubMenu("Developer tools");
            developerTools.add("Experimental crop refinement").setCheckable(true)
                    .setChecked(controller.instanceBoundaryEnabled()).setEnabled(InstanceBoundarySettings.supported())
                    .setOnMenuItemClickListener(item -> { toggleInstanceBoundary();item.setChecked(controller.instanceBoundaryEnabled());return true; });
            developerTools.add("New scans and re-detection copies only").setEnabled(false);
            developerTools.add("New training collection").setOnMenuItemClickListener(item -> {
                nameNewCollection();
                return true;
            });
            developerTools.add("Re-run detection").setEnabled(screen.redetectablePages > 0).setOnMenuItemClickListener(item -> {
                confirmRedetectAlbum();
                return true;
            });
            developerTools.add("Export review ZIP").setEnabled(screen.pageCount > 0).setOnMenuItemClickListener(item -> {
                confirmReviewArchive();
                return true;
            });
        }
        addGalleryAction(menu, "Open-source licences", true, this::showOpenSourceLicenses);
        menu.show();
    }

    private void showOpenSourceLicenses() {
        try {
            TextView text = label(OpenSourceLicenses.read(this), 13, INK, false);
            text.setTextIsSelectable(true);
            text.setPadding(dp(20), dp(12), dp(20), dp(12));
            ScrollView content = new ScrollView(this);
            content.addView(text);
            new AlertDialog.Builder(this).setTitle("Open-source licences")
                    .setView(content).setPositiveButton("Close", null).show();
        } catch (java.io.IOException error) {
            toast("Could not open the bundled licence notices.");
        }
    }
    private void toggleInstanceBoundary(){
        if(!InstanceBoundarySettings.supported()){toast("This boundary experiment is unavailable in this build.");return;}
        boolean enabled=!controller.instanceBoundaryEnabled();controller.setInstanceBoundaryEnabled(enabled);
        toast("Experimental crop refinement "+(enabled?"on":"off")+".");
    }

    private void confirmRedetectAlbum() {
        if (screen == null || !albumInteractive || selecting || screen.redetectablePages == 0) return;
        String albumId = screen.albumId;
        int pageCount = screen.redetectablePages;
        String scans = pageCount + (pageCount == 1 ? " original scan" : " original scans");
        int retainedCount = screen.retainedPageCount;
        String retained = retainedCount == 0 ? "" : " The current album keeps " + retainedCount
                + (retainedCount == 1 ? " photo from a deleted scan" : " photos from deleted scans")
                + " outside the test copy.";
        new AlertDialog.Builder(this)
                .setTitle("Re-run detection")
                .setMessage("PhotoAlbumScan " + BuildConfig.VERSION_NAME + " will copy " + scans
                        + " into a new album and detect fresh crops. The copy inherits the album date and needs extra "
                        + "storage. Crop edits, individual photo dates, and exported JPEGs stay with your current album."
                        + retained)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Re-run in new album", (dialog, which) -> {
                    if (albumInteractive && screen != null && screen.redetectablePages > 0
                            && java.util.Objects.equals(albumId, screen.albumId)) {
                        controller.redetectAlbum();
                    }
                })
                .show();
    }

    private void confirmReviewArchive() {
        if (screen == null || !albumInteractive || selecting || screen.pageCount == 0) return;
        String albumId = screen.albumId;
        LinearLayout fields = column();
        fields.setPadding(dp(24), dp(8), dp(24), dp(8));
        boolean collection = screen.collection;
        fields.addView(label(collection ? "The ZIP includes full-resolution originals and capture metadata. "
                + "The app creates it on this phone. You choose where to share it. Original files may include location or device metadata. Do not post this ZIP in public GitHub issues."
                : "The ZIP includes the whole album: full-resolution scans, personal photos, "
                + "crop settings, and notes. The app creates it on this phone. You choose where to share it. Original files may include location or device metadata. Do not post this ZIP in public GitHub issues.",
                15, INK, false));

        android.widget.Spinner books = new android.widget.Spinner(this);
        var physicalBooks = screen.physicalBooks;
        if (collection && physicalBooks.size() > 1) {
            ArrayList<String> options = new ArrayList<>();
            options.add("All physical albums");
            for (var book : physicalBooks) options.add(book.label());
            var adapter = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, options);
            books.setAdapter(adapter);
            books.setContentDescription("Physical albums to export");
            fields.addView(books, new LinearLayout.LayoutParams(-1, -2));
        }

        EditText issueNote = new EditText(this);
        issueNote.setHint("Issue note (optional)");
        issueNote.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        issueNote.setGravity(Gravity.TOP | Gravity.START);
        issueNote.setMinLines(2);
        issueNote.setMaxLines(4);
        issueNote.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2000)});
        fields.addView(issueNote, new LinearLayout.LayoutParams(-1, -2));

        ScrollView content = new ScrollView(this);
        content.addView(fields);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(collection ? "Export collection ZIP" : "Export review ZIP")
                .setView(content)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create & share", (ignored, which) -> {
                    if (albumInteractive && screen != null && java.util.Objects.equals(albumId, screen.albumId)) {
                        int chosen = books.getSelectedItemPosition();
                        String bookId = collection && chosen > 0 ? physicalBooks.get(chosen - 1).id() : null;
                        controller.exportReviewArchive(issueNote.getText().toString(), bookId);
                    }
                })
                .create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
    }

    void openReviewArchive(Uri archive) {
        Intent share = new Intent(Intent.ACTION_SEND).setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, archive)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        share.setClipData(ClipData.newRawUri("Review ZIP", archive));
        try {
            startActivity(Intent.createChooser(share, "Share review ZIP"));
        } catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
            toast("Review ZIP created. No sharing app could be opened.");
        }
    }

    private void openExportActions() {
        if (screen == null || !albumInteractive || selecting) return;
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, exportMenu, Gravity.END);
        boolean hasPhotos = !shownPhotos(screen).isEmpty();
        addGalleryAction(menu, reviewOnly ? "Export visible JPEGs" : "Export all JPEGs", hasPhotos,
                () -> exportVisible(false));
        addGalleryAction(menu, reviewOnly ? "Export & share visible" : "Export & share all", hasPhotos,
                () -> exportVisible(true));
        if (savedPhotoUri != null) addGalleryAction(menu, "Open saved photo", true, this::openSavedPhoto);
        addGalleryAction(menu, "Open-source licences", true, this::showOpenSourceLicenses);
        menu.show();
    }

    private void exportVisible(boolean share) {
        Set<String> visible = null;
        if (reviewOnly) {
            visible = new LinkedHashSet<>();
            for (ScanController.PhotoTile photo : shownPhotos(screen)) visible.add(photo.id);
        }
        controller.export(visible, share);
    }

    private void addGalleryAction(android.widget.PopupMenu menu, String title, boolean enabled, Runnable action) {
        menu.getMenu().add(title).setEnabled(enabled).setOnMenuItemClickListener(item -> {
            action.run();
            return true;
        });
    }

    private void showAlbumEntries(ScanController.Screen next, boolean enabled) {
        newAlbum.setEnabled(enabled);
        libraryDeveloper.setEnabled(enabled);
        if (next != null && !listedAlbums.equals(next.albums)) {
            listedAlbums = next.albums;
            libraryEntries.removeAllViews();
            for (ScanController.AlbumEntry entry : listedAlbums) {
                button(libraryEntries, entry.name() + " · " + entry.pages()
                        + (entry.pages() == 1 ? " scan" : " scans"), false,
                        () -> controller.switchAlbum(entry.id()));
            }
        }
        libraryEmpty.setVisibility(enabled && listedAlbums.isEmpty() ? View.VISIBLE : View.GONE);
        for (int i = 0; i < libraryEntries.getChildCount(); i++) libraryEntries.getChildAt(i).setEnabled(enabled);
    }

    private void nameNewAlbum() {
        nameNewAlbum(false);
    }

    private void nameNewCollection() {
        nameNewAlbum(true);
    }

    private void nameNewAlbum(boolean collection) {
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint(collection ? "Collection name" : "Album name");
        name.setContentDescription(collection ? "Collection name" : "Album name");
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(36)});
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(collection ? "New training collection" : "New album").setView(name)
                .setNegativeButton("Cancel", null).setPositiveButton("Create", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String title = name.getText().toString().trim();
            if (title.isEmpty()) {
                name.setError("Enter an album name.");
                return;
            }
            dialog.dismiss();
            if (collection) controller.createCollection(title);
            else controller.createAlbum(title);
        });
    }

    private void removeSelectedPhotos() {
        if (!albumInteractive || selectedPhotos.isEmpty()) return;
        Set<String> removing = Set.copyOf(selectedPhotos);
        selecting = false;
        selectedPhotos.clear();
        controller.removePhotos(removing);
    }

    void showSavedPhotos(ArrayList<Uri> photos) {
        savedPhotoUri = photos.isEmpty() ? null : photos.get(photos.size() - 1);
        updateAlbumControls(screen);
    }

    private void openSavedPhoto() {
        if (savedPhotoUri == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(savedPhotoUri, "image/jpeg")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
            toast("Open Gallery and look in Pictures/PhotoAlbumScan.");
        }
    }

    private void setAdjustingCorners(boolean adjusting) {
        adjustingCorners = adjusting;
        showEditorPanels(false);
        scroll.scrollTo(0, 0);
    }

    private void showEditorPanels(boolean originalsFirst) {
        boolean empty = originalsFirst && (screen.collection ? screen.pageCount == 0 : screen.redetectablePages == 0);
        originalsEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        pageEditor.setVisibility(!empty && (originalsFirst || adjustingCorners) ? View.VISIBLE : View.GONE);
        photoEditor.setVisibility(!screen.collection && !empty && (originalsFirst || !adjustingCorners) ? View.VISIBLE : View.GONE);
        pageTitle.setText(originalsFirst ? (screen.collection ? "Original " : "Page ") + (screen.page + 1) + " of " + screen.pageCount
                : "Adjust corners");
        batchStrip.setVisibility(originalsFirst ? View.VISIBLE : View.GONE);
        pageNavigation.setVisibility(originalsFirst ? View.VISIBLE : View.GONE);
        cropNavigation.setVisibility(originalsFirst ? View.VISIBLE : View.GONE);
        retake.setVisibility(originalsFirst && !screen.collection ? View.VISIBLE : View.GONE);
        deleteOriginal.setVisibility(originalsFirst && !screen.retainedCropSource ? View.VISIBLE : View.GONE);
        add.setVisibility(originalsFirst && !screen.collection ? View.VISIBLE : View.GONE);
        remove.setVisibility(screen.collection ? View.GONE : View.VISIBLE);
        showPhoto.setVisibility(originalsFirst ? View.GONE : View.VISIBLE);
        adjustCorners.setVisibility(originalsFirst ? View.GONE : View.VISIBLE);
        ((LinearLayout.LayoutParams) photoEditor.getLayoutParams()).topMargin = originalsFirst ? dp(10) : 0;
    }

    private void choosePhoto(String id) {
        if (!albumInteractive) return;
        if (selecting) {
            if (!selectedPhotos.remove(id)) selectedPhotos.add(id);
            updateAlbumControls(screen);
        } else controller.openPhoto(id);
    }

    private void toggleSelection() {
        if (!albumInteractive) return;
        selecting = !selecting;
        selectedPhotos.clear();
        updateAlbumControls(screen);
    }

    private void toggleSelectAll() {
        if (!albumInteractive || !selecting) return;
        List<ScanController.PhotoTile> shown = shownPhotos(screen);
        boolean all = selectedPhotos.size() == shown.size();
        selectedPhotos.clear();
        if (!all) for (ScanController.PhotoTile photo : shown) selectedPhotos.add(photo.id);
        updateAlbumControls(screen);
    }

    private void openAlbumSettings() {
        new AlertDialog.Builder(this).setTitle("Album settings")
                .setItems(new String[]{"Edit album name", "Edit album date"}, (dialog, which) -> {
                    if (which == 0) editAlbum();
                    else editAlbumDate();
                }).show();
    }

    void openShare(ArrayList<Uri> outputs) {
        if (isFinishing() || isDestroyed() || outputs.isEmpty()) return;
        Intent share = new Intent(outputs.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE)
                .setType("image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ClipData files = ClipData.newRawUri("PhotoAlbumScan photos", outputs.get(0));
        for (int i = 1; i < outputs.size(); i++) files.addItem(new ClipData.Item(outputs.get(i)));
        share.setClipData(files);
        if (outputs.size() == 1) share.putExtra(Intent.EXTRA_STREAM, outputs.get(0));
        else share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, outputs);
        try {
            startActivity(Intent.createChooser(share, "Share photos"));
        } catch (RuntimeException unavailable) {
            toast("JPEGs saved. No sharing app could be opened.");
        }
    }

    void render(ScanController.Screen next, boolean ready, boolean working, String notice) {
        if (next != null && !java.util.Objects.equals(displayedAlbum, next.albumId)) {
            displayedAlbum = next.albumId;
            reviewOnly = false;
            selecting = false;
            selectedPhotos.clear();
            galleryPosition = null;
            savedPhotoUri = null;
        }
        boolean browsingAlbums = next == null || next.libraryVisible;
        boolean editing = !browsingAlbums && next.editing;
        boolean emptyOriginals = editing && next.originalsFirst && (next.collection ? next.pageCount == 0 : next.redetectablePages == 0);
        boolean inAlbum = !browsingAlbums && !editing;
        if (browsingAlbums && screen != null && !screen.libraryVisible) {
            selecting = false;
            selectedPhotos.clear();
            reviewOnly = false;
            galleryPosition = null;
            scroll.scrollTo(0, 0);
        }
        if (editing && screen != null && screen.review && !screen.editing) {
            galleryPosition = grid.onSaveInstanceState();
            adjustingCorners = false;
        }
        boolean enabled = ready && !working && external == 0;
        boolean hasPages = next != null && next.pageCount > 0;
        boolean pending = next != null && !next.pending.isEmpty();
        boolean deleting = next != null && next.pendingDelete;
        boolean hasCrop = next != null && !next.corners.isEmpty();
        boolean hasImage = next != null && next.source != null;
        masthead.setVisibility(inAlbum ? View.GONE : View.VISIBLE);
        library.setVisibility(browsingAlbums ? View.VISIBLE : View.GONE);
        if (browsingAlbums) showAlbumEntries(next, enabled && !pending);
        review.setVisibility(editing ? View.VISIBLE : View.GONE);
        editorFooter.setVisibility(editing ? View.VISIBLE : View.GONE);
        scroll.setVisibility(inAlbum ? View.GONE : View.VISIBLE);
        album.setVisibility(inAlbum ? View.VISIBLE : View.GONE);
        recovery.setVisibility(pending && external == 0 ? View.VISIBLE : View.GONE);
        mode.setText(browsingAlbums ? "Albums" : editing && next.originalsFirst ? "Original scans" : "Photo editor");
        progress.setVisibility(working ? View.VISIBLE : View.INVISIBLE);
        if (notice.isEmpty() && pending && external == 0) notice = "A camera shot is waiting. Recover or discard it.";
        status.setText(notice);
        status.setVisibility(notice.isEmpty() ? View.GONE : View.VISIBLE);

        recover.setEnabled(enabled);
        discard.setEnabled(enabled);
        back.setEnabled(enabled);
        albumInteractive = inAlbum && enabled && !pending && !deleting;
        if (inAlbum) updateAlbumControls(next);
        if (albumInteractive && galleryPosition != null) {
            grid.onRestoreInstanceState(galleryPosition);
            galleryPosition = null;
        }
        prevPage.setEnabled(enabled && hasPages && next.page > 0);
        nextPage.setEnabled(enabled && hasPages && next.page + 1 < next.pageCount);
        batchStrip.setInteractive(enabled && hasPages);
        retake.setEnabled(enabled && hasPages && !pending);
        deleteOriginal.setEnabled(enabled && hasPages && !pending && !next.retainedCropSource
                && !next.currentSourceId.isEmpty());
        prevCrop.setEnabled(enabled && hasCrop && next.selection > 0);
        nextCrop.setEnabled(enabled && hasCrop && next.selection + 1 < next.corners.size());
        add.setEnabled(enabled && hasImage && next.corners.size() < ScanSession.MAX_CROPS);
        remove.setEnabled(enabled && hasCrop);
        rotate.setEnabled(enabled && hasCrop);
        editNotes.setEnabled(enabled && hasCrop);
        markChecked.setVisibility(hasCrop && next.cropNeedsReview ? View.VISIBLE : View.GONE);
        markChecked.setEnabled(enabled && hasCrop);
        adjustCorners.setEnabled(enabled && hasImage);
        showPhoto.setEnabled(enabled);
        cropView.setEnabled(enabled && hasImage);

        if (next == null || next == screen) return;
        if (screen == null || screen.editing != next.editing || screen.page != next.page
                || !screen.currentSourceId.equals(next.currentSourceId)) scroll.scrollTo(0, 0);
        screen = next;
        if (editing) showEditorPanels(next.originalsFirst);
        batchStrip.showPages(emptyOriginals ? List.of() : next.originals, editing && !emptyOriginals ? next.page : -1);
        int cropCount = next.corners.size();
        cropView.show(emptyOriginals ? null : next.source, emptyOriginals ? List.of() : next.corners,
                hasCrop && !emptyOriginals ? next.selection : -1);
        if (!hasCrop) cropTitle.setText("No crops on this page");
        else if (next.originalsFirst) {
            cropTitle.setText("Corrected preview · Photo " + (next.selection + 1) + " of " + cropCount);
        } else cropTitle.setText("Page " + (next.page + 1) + " · Photo " + (next.selection + 1));
        canvasHint.setText(!hasImage ? "This page preview is unavailable."
                : next.collection ? "Full-resolution original saved. Pinch to zoom."
                : hasCrop ? "Pinch to zoom, pan the page, then drag a corner to fit the print."
                : "Tap Add crop to outline a photo.");
        if (!next.pageStatus.isEmpty()) canvasHint.append("\n" + next.pageStatus);
        cropDetail.setVisibility(hasCrop ? View.VISIBLE : View.GONE);
        corrected.setImageBitmap(emptyOriginals ? null : next.corrected);
        String date = next.effectiveDate;
        if (!date.isEmpty() && next.dateNote.isEmpty()) date += " (album date)";
        String notes = date.isEmpty() ? next.caption
                : next.caption.isEmpty() ? date : date + " · " + next.caption;
        photoNotes.setText(notes);
        photoNotes.setVisibility(notes.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void openCamera(boolean retake) {
        Intent camera = new Intent(this, CaptureActivity.class).putExtra(CaptureActivity.EXTRA_RETAKE, retake);
        try {
            external = CAMERA;
            controller.redraw();
            startActivityForResult(camera, CAMERA);
        } catch (RuntimeException unavailable) {
            external = 0;
            controller.redraw();
            toast("Could not open the camera.");
        }
    }

    private void openGallery() {
        Intent gallery = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            external = GALLERY;
            controller.redraw();
            startActivityForResult(gallery, GALLERY);
        } catch (RuntimeException unavailable) {
            external = 0;
            controller.redraw();
            toast("Could not open the photo picker.");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == CAMERA) {
            external = 0;
            boolean review = false;
            boolean retake = screen != null && screen.editing;
            if (data != null) {
                review = data.getBooleanExtra(CaptureActivity.EXTRA_REVIEW, review);
                retake = data.getBooleanExtra(CaptureActivity.EXTRA_RETAKE, retake);
                String notice = data.getStringExtra(CaptureActivity.EXTRA_NOTICE);
                if (notice != null) toast(notice);
                if (data.getBooleanExtra(CaptureActivity.EXTRA_IMPORT, false)) {
                    galleryFromCamera = true;
                    openGallery();
                    return;
                }
                String sourceId = data.getStringExtra(CaptureActivity.EXTRA_REVIEW_SOURCE_ID);
                if (sourceId != null && !sourceId.isEmpty()) {
                    controller.reviewScan(sourceId);
                    return;
                }
            }
            controller.cameraClosed(review, retake);
        } else if (request == GALLERY) {
            external = 0;
            LinkedHashSet<Uri> images = new LinkedHashSet<>();
            if (result == RESULT_OK && data != null) {
                ClipData clips = data.getClipData();
                if (clips != null) {
                    for (int i = 0; i < clips.getItemCount(); i++) {
                        Uri uri = clips.getItemAt(i).getUri();
                        if (uri != null) images.add(uri);
                    }
                }
                if (data.getData() != null) images.add(data.getData());
            }
            if (!images.isEmpty()) controller.importPhotos(new ArrayList<>(images));
            else if (galleryFromCamera) controller.cameraClosed(false, false);
            else controller.redraw();
            galleryFromCamera = false;
        }
    }

    @Override public void onBackPressed() {
        if (screen == null || screen.libraryVisible) {
            super.onBackPressed();
            return;
        }
        if (selecting && albumInteractive) {
            toggleSelection();
            return;
        }
        if (screen.editing) {
            if (!screen.originalsFirst && adjustingCorners) {
                setAdjustingCorners(false);
                return;
            }
            controller.openAlbum();
            return;
        }
        if (albumInteractive) controller.showLibrary();
    }

    private void editAlbum() {
        EditText entry = new EditText(this);
        entry.setSingleLine();
        entry.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        entry.setFilters(new InputFilter[]{new InputFilter.LengthFilter(36)});
        entry.setText(screen.album);
        entry.selectAll();
        entry.setContentDescription("Album name");
        FrameLayout input = new FrameLayout(this);
        input.setPadding(dp(24), dp(8), dp(24), 0);
        input.addView(entry);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Edit album name").setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (ignored, which) -> controller.rename(entry.getText().toString()))
                .create();
        entry.requestFocus();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dialog.show();
    }

    private void editAlbumDate() {
        LinearLayout fields = column();
        fields.setPadding(dp(24), dp(8), dp(24), 0);
        EditText date = dateField(fields, "Album date (optional)", screen.albumDate,
                "Applies to photos that use the album date, including photos added later.");
        showDateDialog("Edit album date", fields, date, "Clear date",
                () -> controller.updateAlbumDate(date.getText().toString()));
    }

    private void editPhotoNotes() {
        LinearLayout fields = column();
        fields.setPadding(dp(24), dp(8), dp(24), 0);
        String fallback = screen.albumDate.isEmpty() ? "Leave blank to follow the album date when one is set."
                : "Leave blank to use the album date (" + screen.albumDate + ").";
        EditText date = dateField(fields, "Photo date (optional)", screen.dateNote, fallback);

        EditText caption = new EditText(this);
        caption.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        caption.setHint("Caption (optional)");
        caption.setGravity(Gravity.TOP | Gravity.START);
        caption.setMinLines(2);
        caption.setMaxLines(6);
        caption.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1000)});
        caption.setText(screen.caption);
        fields.addView(caption, new LinearLayout.LayoutParams(-1, -2));

        showDateDialog("Edit date & notes", fields, date, "Use album date",
                () -> controller.updateNotes(caption.getText().toString(), date.getText().toString()));
    }

    private EditText dateField(LinearLayout fields, String hint, String value, String fallback) {
        EditText date = new EditText(this);
        date.setSingleLine(true);
        date.setInputType(InputType.TYPE_CLASS_TEXT);
        date.setHint(hint);
        date.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        date.setText(value);
        fields.addView(date, new LinearLayout.LayoutParams(-1, -2));
        button(fields, "Choose exact date", false, () -> chooseExactDate(date, "Photo date (optional)".equals(hint) ? screen.effectiveDate : screen.albumDate));
        fields.addView(label(fallback + "\nEnter YYYY-MM-DD for an exact date or an estimate like summer 1979.",
                13, MUTED, false));
        return date;
    }

    private void editSelectedDate() {
        Set<String> ids = Set.copyOf(selectedPhotos);
        if (ids.isEmpty()) return;
        LinearLayout fields = column();
        fields.setPadding(dp(20), 0, dp(20), 0);
        EditText date = dateField(fields, "Photo date", "", "Leave blank to use the album date.");
        showDateDialog("Set date for " + ids.size() + (ids.size() == 1 ? " photo" : " photos"),
                fields, date, "Use album date", () -> controller.setSelectedPhotoDate(ids, date.getText().toString().trim()));
    }

    private void chooseExactDate(EditText destination, String fallback) {
        String value = destination.getText().toString().trim();
        java.time.LocalDate initial = PhotoDate.isCalendarDate(value) ? java.time.LocalDate.parse(value)
                : PhotoDate.isCalendarDate(fallback) ? java.time.LocalDate.parse(fallback) : java.time.LocalDate.now();
        LinearLayout fields = column();
        fields.setPadding(dp(8), dp(8), dp(8), 0);
        fields.setFocusableInTouchMode(true);
        LinearLayout navigation = row(fields);
        EditText year = dateNumber(navigation, "Year", 4, String.format(java.util.Locale.ROOT, "%04d", initial.getYear()));
        year.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        year.setContentDescription("Year");
        android.widget.Spinner month = new android.widget.Spinner(this);
        month.setContentDescription("Month");
        String[] monthNames = new java.text.DateFormatSymbols().getMonths();
        android.widget.ArrayAdapter<String> months = new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, java.util.Arrays.copyOf(monthNames, 12));
        month.setAdapter(months);
        month.setMinimumHeight(dp(48));
        month.setSelection(initial.getMonthValue() - 1);
        navigation.addView(month, new LinearLayout.LayoutParams(0, -2, 2));
        LinearLayout calendar = column();
        fields.addView(calendar);
        ScrollView content = new ScrollView(this);
        content.addView(fields);
        int[] chosenDay = {initial.getDayOfMonth()};
        AlertDialog picker = new AlertDialog.Builder(this).setTitle("Choose exact date").setView(content)
                .setNegativeButton("Cancel", null).setPositiveButton("Use date", null).create();
        picker.show();
        if (picker.getWindow() != null) picker.getWindow().setLayout(-1, -2);
        fields.requestFocus();
        Runnable refresh = () -> {
            String text = year.getText().toString();
            java.time.YearMonth shown = text.matches("[0-9]{4}") && !text.equals("0000")
                    ? java.time.YearMonth.of(Integer.parseInt(text), month.getSelectedItemPosition() + 1) : null;
            picker.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(shown != null);
            if (shown != null) chosenDay[0] = Math.min(chosenDay[0], shown.lengthOfMonth());
            showCalendar(calendar, shown, chosenDay[0], day -> {
                chosenDay[0] = day;
                hideDateKeyboard(year);
                for (int i = 1; i < calendar.getChildCount(); i++) {
                    LinearLayout week = (LinearLayout) calendar.getChildAt(i);
                    for (int j = 0; j < week.getChildCount(); j++) {
                        View cell = week.getChildAt(j);
                        if (cell instanceof Button button) button.setSelected(button.getText().toString().equals(Integer.toString(day)));
                    }
                }
            });
        };
        year.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { refresh.run(); }
            @Override public void afterTextChanged(android.text.Editable value) {}
        });
        month.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                hideDateKeyboard(year);
                refresh.run();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        refresh.run();
        picker.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            destination.setText(String.format(java.util.Locale.ROOT, "%s-%02d-%02d", year.getText().toString(),
                    month.getSelectedItemPosition() + 1, chosenDay[0]));
            destination.setError(null);
            picker.dismiss();
        });
    }

    private void hideDateKeyboard(EditText field) {
        ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(field.getWindowToken(), 0);
    }

    private void showCalendar(LinearLayout calendar, java.time.YearMonth month, int selected,
                              java.util.function.IntConsumer choose) {
        calendar.removeAllViews();
        if (month == null) {
            calendar.addView(label("Enter a four-digit year.", 14, MUTED, false));
            return;
        }
        java.util.Locale locale = getResources().getConfiguration().getLocales().get(0);
        java.time.DayOfWeek first = java.time.temporal.WeekFields.of(locale).getFirstDayOfWeek();
        LinearLayout weekdays = row(calendar);
        for (int i = 0; i < 7; i++) {
            TextView day = label(first.plus(i).getDisplayName(java.time.format.TextStyle.NARROW, locale), 13, MUTED, true);
            day.setGravity(Gravity.CENTER);
            weekdays.addView(day, new LinearLayout.LayoutParams(0, dp(26), 1));
        }
        int offset = Math.floorMod(month.atDay(1).getDayOfWeek().getValue() - first.getValue(), 7);
        for (int weekStart = 1 - offset; weekStart <= month.lengthOfMonth(); weekStart += 7) {
            LinearLayout week = row(calendar);
            for (int i = 0; i < 7; i++) {
                int day = weekStart + i;
                View cell;
                if (day <= 0 || day > month.lengthOfMonth()) {
                    cell = new View(this);
                } else {
                    Button button = new Button(this, null, android.R.attr.borderlessButtonStyle);
                    button.setText(Integer.toString(day));
                    button.setTextSize(14);
                    button.setPadding(0, 0, 0, 0);
                    button.setMinWidth(0);
                    button.setMinimumWidth(0);
                    button.setSelected(day == selected);
                    button.setTextColor(new ColorStateList(new int[][]{new int[]{android.R.attr.state_selected}, new int[]{}},
                            new int[]{Color.WHITE, INK}));
                    android.graphics.drawable.StateListDrawable background = new android.graphics.drawable.StateListDrawable();
                    background.addState(new int[]{android.R.attr.state_selected}, rounded(TEAL, 8, Color.TRANSPARENT));
                    background.addState(new int[]{}, rounded(Color.TRANSPARENT, 8, Color.TRANSPARENT));
                    button.setBackground(background);
                    button.setContentDescription(month.atDay(day)
                            .format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.LONG).withLocale(locale)));
                    button.setOnClickListener(view -> choose.accept(day));
                    cell = button;
                }
                week.addView(cell, new LinearLayout.LayoutParams(0, dp(48), 1));
            }
        }
    }

    private EditText dateNumber(LinearLayout fields, String title, int digits, String value) {
        EditText input = new EditText(this);
        input.setSingleLine();
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789"));
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(digits)});
        input.setHint(title);
        input.setText(value);
        fields.addView(input, new LinearLayout.LayoutParams(-1, -2));
        return input;
    }

    private void showDateDialog(String title, LinearLayout fields, EditText date, String clear, Runnable save) {
        String initialDate = date.getText().toString().trim();
        ScrollView content = new ScrollView(this);
        content.addView(fields);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(content)
                .setNeutralButton(clear, null)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
            date.setText("");
            date.setError(null);
        });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = date.getText().toString().trim();
            if (!value.equals(initialDate) && PhotoDate.looksLikeCalendarDate(value) && !PhotoDate.isCalendarDate(value)) {
                date.requestFocus();
                date.setError("Enter a valid date in YYYY-MM-DD format.");
                return;
            }
            dialog.dismiss();
            save.run();
        });
    }

    private void confirmDeleteAlbum() {
        if (screen == null || !albumInteractive || selecting) return;
        String albumId = screen.albumId;
        String albumName = screen.album;
        new AlertDialog.Builder(this)
                .setTitle("Delete album?")
                .setMessage("Delete “" + albumName + "” and its original scans and cropped photos from PhotoAlbumScan? "
                        + "Exported JPEGs stay on your phone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete album", (dialog, which) -> {
                    if (albumInteractive && screen != null && java.util.Objects.equals(albumId, screen.albumId)) {
                        controller.deleteAlbum(albumId);
                    }
                })
                .show();
    }

    private void confirmRetake() {
        new AlertDialog.Builder(this)
                .setTitle("Retake this page?")
                .setMessage("The new shot replaces this page and its crop edits. JPEGs already in Gallery stay there.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open camera", (dialog, which) -> openCamera(true))
                .show();
    }

    private void confirmDeleteOriginal() {
        if (screen == null || !deleteOriginal.isEnabled() || !screen.originalsFirst || screen.retainedCropSource) return;
        String albumId = screen.albumId;
        String sourceId = screen.currentSourceId;
        boolean hasCrops = !screen.corners.isEmpty();
        LinearLayout choices = column();
        choices.setPadding(dp(24), dp(4), dp(24), dp(8));
        choices.addView(label(hasCrops
                ? "Keep crops at full resolution. Later edits use only those pixels."
                : "Delete this scan from the album. Find previously exported JPEGs in Gallery.", 14, INK, false));
        ScrollView content = new ScrollView(this);
        content.addView(choices);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Delete original scan?")
                .setView(content).create();
        if (hasCrops) button(choices, "Keep cropped photos", false, () -> {
            dialog.dismiss();
            deleteOriginal(albumId, sourceId, true);
        });
        button(choices, hasCrops ? "Delete page and photos" : "Delete page", false, () -> {
            dialog.dismiss();
            deleteOriginal(albumId, sourceId, false);
        }).setTextColor(WARNING);
        button(choices, "Cancel", false, dialog::dismiss);
        dialog.show();
    }

    private void deleteOriginal(String albumId, String sourceId, boolean keepCrops) {
        if (screen != null && deleteOriginal.isEnabled() && java.util.Objects.equals(albumId, screen.albumId)
                && sourceId.equals(screen.currentSourceId)) {
            controller.deleteCurrentPage(sourceId, keepCrops);
        }
    }

    private void toast(String text) { android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show(); }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.TOP);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    private LinearLayout card() {
        LinearLayout view = column();
        view.setBackground(rounded(Color.WHITE, 17, STROKE));
        view.setPadding(dp(14), dp(14), dp(14), dp(14));
        return view;
    }

    private void addCard(LinearLayout parent, LinearLayout child) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(10);
        parent.addView(child, params);
    }

    private TextView label(String text, int size, int color, boolean strong) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (strong) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(LinearLayout parent, String title, boolean primary, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{0xFF657A76, primary ? Color.WHITE : TEAL}));
        int fill = primary ? TEAL : SOFT;
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x3300695D), rounded(fill, 12, Color.TRANSPARENT), null));
        button.setMinimumHeight(dp(48));
        button.setMinHeight(dp(48));
        button.setPadding(dp(8), dp(6), dp(8), dp(6));
        LinearLayout.LayoutParams params;
        if (parent.getOrientation() == LinearLayout.HORIZONTAL) {
            params = new LinearLayout.LayoutParams(0, -2, 1);
            if (parent.getChildCount() > 0) params.leftMargin = dp(8);
        } else {
            params = new LinearLayout.LayoutParams(-1, -2);
        }
        params.topMargin = dp(7);
        parent.addView(button, params);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private GradientDrawable rounded(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
