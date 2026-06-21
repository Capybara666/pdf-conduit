package com.pdfconduit.app.gui.component;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import com.pdfconduit.app.gui.Ui;
import com.pdfconduit.app.gui.util.PdfPageSource;
import com.pdfconduit.app.i18n.I18n;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A reusable, in-app PDF viewer: page navigation, zoom (fit-width / fit-page / a
 * stepped percentage) and a clickable thumbnail rail. Pages render lazily on a
 * single background thread at the DPI the current zoom calls for, so even large
 * documents stay responsive; a small cache keeps recently shown renders.
 *
 * <p>Designed to be dropped into any panel that wants to show the document being
 * worked on (Preview today; Rotate / page-numbering / redaction later). Drive it
 * with {@link #load(Path)}; it owns its background work and cleans up the previous
 * document when a new one is loaded.
 */
public final class PdfViewer extends BorderPane {

    private enum Fit { WIDTH, PAGE, NONE }

    /** DPI = 72 · scale; clamp scale so renders never get absurdly small or huge. */
    private static final double MIN_SCALE = 0.1, MAX_SCALE = 4.0;
    /** Hard cap on a rendered page's longest side (px), so a huge page can't OOM. */
    private static final double MAX_PX = 4000;
    /** Page-area padding (must match {@code .viewer-page-area} in base.css: 14 each side). */
    private static final double PAGE_PAD = 28;
    private static final double ZOOM_STEP = 1.25;
    private static final int THUMB_DPI = 20;
    private static final double THUMB_W = 96;
    /** How far above/below the thumbnail viewport to pre-render, so scrolling feels instant. */
    private static final double THUMB_PREFETCH = 240;
    private static final int CACHE_LIMIT = 12;

    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");

    // --- background rendering --------------------------------------------
    private final ExecutorService exec =
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "pdf-viewer-render");
            t.setDaemon(true);
            return t;
        });
    // Touched only on the exec thread (single-threaded ⇒ no further synchronisation).
    private PdfPageSource source;

    // --- viewer state (FX thread) ----------------------------------------
    private int pageCount;
    private int pageIndex;             // 0-based
    private float[][] sizesPt = new float[0][];
    private Fit fit = Fit.WIDTH;
    private double zoom = 1.0;         // used when fit == NONE
    private double lastScale = 1.0;    // most recent effective scale (for label + NONE seed)
    private long renderToken;          // discards stale page renders
    private long loadToken;            // discards stale loads / thumbnails
    private double renderedViewportW = -1;   // viewport width the current render fit to
    private final Map<String, Image> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Image> e) {
            return size() > CACHE_LIMIT;
        }
    };

    // --- nodes ------------------------------------------------------------
    private final ImageView pageView = new ImageView();
    private final ScrollPane pageScroll = new ScrollPane();
    private final StackPane pageArea = new StackPane();
    private final Label placeholder = new Label();
    private final VBox thumbRail = new VBox(8);
    private final ScrollPane thumbScroll = new ScrollPane();
    // Parallel arrays, one slot per page. Tiles exist immediately as sized placeholders;
    // each page's thumbnail is rendered lazily only when its tile nears the viewport.
    private StackPane[] thumbHolders = new StackPane[0];
    private ImageView[] thumbImages = new ImageView[0];
    private boolean[] thumbRequested = new boolean[0];

    private final Button prevBtn = new Button("◀");
    private final Button nextBtn = new Button("▶");
    private final TextField pageField = new TextField();
    private final Label countLabel = new Label();
    private final Button zoomOutBtn = new Button("−");
    private final Button zoomInBtn = new Button("+");
    private final Label zoomLabel = new Label();
    private final Button fitWidthBtn = new Button();
    private final Button fitPageBtn = new Button();
    private final Label statusLabel = new Label();

    private final PauseTransition resizeDebounce = new PauseTransition(Duration.millis(180));
    private final PauseTransition thumbScrollDebounce = new PauseTransition(Duration.millis(50));

    public PdfViewer() {
        getStyleClass().add("pdf-viewer");

        setTop(buildToolbar());
        setCenter(buildPageArea());
        setLeft(buildThumbRail());

        resizeDebounce.setOnFinished(e -> { if (fit != Fit.NONE) requestRender(); });
        // Re-fit when the viewport width changes (only matters for fit modes). A small
        // threshold ignores the few-pixel jitter from a scrollbar appearing, which would
        // otherwise oscillate (narrower → smaller page → scrollbar gone → wider → …).
        pageScroll.viewportBoundsProperty().addListener((o, a, b) -> {
            if (fit != Fit.NONE && Math.abs(b.getWidth() - renderedViewportW) > 6)
                resizeDebounce.playFromStart();
        });

        // Lazily render only the thumbnails near the rail's viewport, as the user scrolls.
        thumbScrollDebounce.setOnFinished(e -> renderVisibleThumbs());
        thumbScroll.vvalueProperty().addListener((o, a, b) -> thumbScrollDebounce.playFromStart());
        thumbScroll.viewportBoundsProperty().addListener((o, a, b) -> thumbScrollDebounce.playFromStart());

        I18n.addListener(this::retranslate);
        showEmpty(true);
        updateControls();
    }

    // --- public API -------------------------------------------------------

    /** Loads {@code pdf}, replacing whatever was shown; renders the first page. */
    public void load(Path pdf) {
        long token = ++loadToken;
        renderToken++;                  // abandon any in-flight page render
        cache.clear();
        statusLabel.setText(I18n.t("preview.loading", pdf.getFileName()));
        showEmpty(false);
        clearThumbs();
        exec.submit(() -> {
            try {
                PdfPageSource opened = new PdfPageSource(pdf);
                if (source != null) { try { source.close(); } catch (Exception ignored) {} }
                source = opened;
                int count = opened.pageCount();
                float[][] sizes = opened.sizesPt();
                Platform.runLater(() -> {
                    if (token != loadToken) return;
                    pageCount = count;
                    sizesPt = sizes;
                    pageIndex = 0;
                    fit = Fit.WIDTH;
                    statusLabel.setText("");
                    buildThumbPlaceholders();
                    updateControls();
                    requestRender();
                    Platform.runLater(this::renderVisibleThumbs);   // once the rail has laid out
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (token != loadToken) return;
                    showEmpty(true);
                    statusLabel.setText(I18n.t("preview.loadfail",
                        ex.getMessage() == null ? "?" : ex.getMessage()));
                });
            }
        });
    }

    /** Clears the viewer back to its empty state. */
    public void clear() {
        loadToken++;
        renderToken++;
        cache.clear();
        pageCount = 0;
        sizesPt = new float[0][];
        pageView.setImage(null);
        clearThumbs();
        statusLabel.setText("");
        showEmpty(true);
        updateControls();
    }

    // --- toolbar ----------------------------------------------------------

    private HBox buildToolbar() {
        for (Button b : new Button[]{prevBtn, nextBtn, zoomOutBtn, zoomInBtn, fitWidthBtn, fitPageBtn}) {
            b.getStyleClass().add("viewer-btn");
            b.setFocusTraversable(false);
        }
        prevBtn.setOnAction(e -> goTo(pageIndex - 1));
        nextBtn.setOnAction(e -> goTo(pageIndex + 1));
        tip(prevBtn, "preview.nav.prev");
        tip(nextBtn, "preview.nav.next");

        pageField.getStyleClass().add("viewer-page-field");
        pageField.setPrefColumnCount(3);
        pageField.setAlignment(Pos.CENTER);
        pageField.setOnAction(e -> jumpToTypedPage());
        pageField.focusedProperty().addListener((o, was, now) -> { if (!now) syncPageField(); });
        countLabel.getStyleClass().add("text-caption");

        zoomOutBtn.setOnAction(e -> stepZoom(1 / ZOOM_STEP));
        zoomInBtn.setOnAction(e -> stepZoom(ZOOM_STEP));
        tip(zoomOutBtn, "preview.zoom.out");
        tip(zoomInBtn, "preview.zoom.in");
        zoomLabel.getStyleClass().add("text-caption");
        zoomLabel.setMinWidth(44);
        zoomLabel.setAlignment(Pos.CENTER);

        I18n.bindText(fitWidthBtn::setText, "preview.zoom.fitwidth");
        I18n.bindText(fitPageBtn::setText, "preview.zoom.fitpage");
        fitWidthBtn.setOnAction(e -> setFit(Fit.WIDTH));
        fitPageBtn.setOnAction(e -> setFit(Fit.PAGE));

        statusLabel.getStyleClass().add("text-status");

        HBox nav = new HBox(Ui.INLINE_GAP, prevBtn, pageField, countLabel, nextBtn);
        nav.setAlignment(Pos.CENTER_LEFT);
        HBox zoomBox = new HBox(Ui.INLINE_GAP, zoomOutBtn, zoomLabel, zoomInBtn, fitWidthBtn, fitPageBtn);
        zoomBox.setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(Ui.INLINE_GAP, nav, statusLabel, spacer, zoomBox);
        bar.getStyleClass().add("viewer-toolbar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private Region buildPageArea() {
        pageView.setPreserveRatio(true);
        pageView.setSmooth(true);
        I18n.bindText(placeholder::setText, "preview.empty");
        placeholder.getStyleClass().add("viewer-placeholder");
        pageArea.getChildren().addAll(pageView, placeholder);
        pageArea.setAlignment(Pos.CENTER);
        pageArea.getStyleClass().add("viewer-page-area");

        pageScroll.setContent(pageArea);
        pageScroll.setPannable(true);
        pageScroll.setFitToWidth(true);
        pageScroll.setFitToHeight(true);
        pageScroll.getStyleClass().add("viewer-scroll");
        return pageScroll;
    }

    private Region buildThumbRail() {
        thumbRail.setAlignment(Pos.TOP_CENTER);
        thumbRail.getStyleClass().add("viewer-thumb-rail");
        thumbScroll.setContent(thumbRail);
        thumbScroll.setFitToWidth(true);
        thumbScroll.setMinWidth(132);
        thumbScroll.setPrefWidth(132);
        thumbScroll.getStyleClass().add("viewer-thumb-scroll");
        return thumbScroll;
    }

    // --- navigation -------------------------------------------------------

    private void goTo(int index) {
        if (pageCount == 0) return;
        int clamped = Math.max(0, Math.min(pageCount - 1, index));
        if (clamped == pageIndex && pageView.getImage() != null) return;
        pageIndex = clamped;
        updateControls();
        requestRender();
        scrollThumbIntoView();
    }

    private void jumpToTypedPage() {
        try {
            goTo(Integer.parseInt(pageField.getText().trim()) - 1);
        } catch (NumberFormatException ex) {
            syncPageField();
        }
    }

    // --- zoom -------------------------------------------------------------

    private void setFit(Fit mode) {
        fit = mode;
        requestRender();
    }

    private void stepZoom(double factor) {
        // Leaving a fit mode: seed the manual zoom from what's on screen now.
        if (fit != Fit.NONE) zoom = lastScale;
        fit = Fit.NONE;
        zoom = clampScale(zoom * factor);
        requestRender();
    }

    // --- rendering --------------------------------------------------------

    private void requestRender() {
        if (pageCount == 0) return;
        double scale = computeScale();
        lastScale = scale;
        float dpi = (float) (72 * scale);
        updateZoomLabel(scale);

        int page = pageIndex;
        String key = page + "@" + Math.round(dpi);
        Image hit = cache.get(key);
        if (hit != null) { pageView.setImage(hit); fitPageView(hit); return; }

        long token = ++renderToken;
        exec.submit(() -> {
            try {
                if (source == null) return;
                Image img = source.render(page, dpi);
                Platform.runLater(() -> {
                    if (token != renderToken) return;
                    cache.put(key, img);
                    pageView.setImage(img);
                    fitPageView(img);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (token == renderToken)
                        statusLabel.setText(I18n.t("preview.loadfail",
                            ex.getMessage() == null ? "?" : ex.getMessage()));
                });
            }
        });
    }

    /** Effective render scale for the current page under the current fit/zoom. */
    private double computeScale() {
        float[] size = (pageIndex < sizesPt.length) ? sizesPt[pageIndex] : null;
        if (size == null || size[0] <= 0 || size[1] <= 0) return clampScale(zoom);
        Bounds vp = pageScroll.getViewportBounds();
        renderedViewportW = vp.getWidth();
        // Leave the padding (plus a hair) so a fit-width page never trips a scrollbar.
        double availW = Math.max(80, vp.getWidth() - PAGE_PAD - 2);
        double availH = Math.max(80, vp.getHeight() - PAGE_PAD - 2);
        double scale = switch (fit) {
            case WIDTH -> availW / size[0];
            case PAGE  -> Math.min(availW / size[0], availH / size[1]);
            case NONE  -> zoom;
        };
        // Never let a single render exceed the pixel cap.
        double maxByPixels = MAX_PX / Math.max(size[0], size[1]);
        return clampScale(Math.min(scale, maxByPixels));
    }

    private static double clampScale(double s) { return Math.max(MIN_SCALE, Math.min(MAX_SCALE, s)); }

    /**
     * Shows the rendered image at its natural pixel size (it was already rendered to
     * fit). The page area's min size is pinned to the image so the ScrollPane — with
     * fit-to-width/height on — centres the page when it is smaller than the viewport
     * but still lets it scroll once a zoomed-in page grows past the viewport.
     */
    private void fitPageView(Image img) {
        pageView.setFitWidth(img.getWidth());
        pageView.setFitHeight(img.getHeight());
        pageArea.setMinWidth(img.getWidth() + PAGE_PAD);
        pageArea.setMinHeight(img.getHeight() + PAGE_PAD);
    }

    // --- thumbnails -------------------------------------------------------

    /** Removes every thumbnail tile and forgets their render state. */
    private void clearThumbs() {
        thumbRail.getChildren().clear();
        thumbHolders = new StackPane[0];
        thumbImages = new ImageView[0];
        thumbRequested = new boolean[0];
    }

    /**
     * Builds one correctly-sized placeholder tile per page (no rendering yet), so the
     * rail's height — and thus its scrollbar — is right immediately even for a long
     * document. Thumbnails fill in lazily via {@link #renderVisibleThumbs()}.
     */
    private void buildThumbPlaceholders() {
        int n = pageCount;
        thumbHolders = new StackPane[n];
        thumbImages = new ImageView[n];
        thumbRequested = new boolean[n];
        thumbRail.getChildren().clear();
        for (int i = 0; i < n; i++) {
            final int page = i;
            ImageView iv = new ImageView();
            iv.setFitWidth(THUMB_W);
            iv.setPreserveRatio(true);
            iv.setSmooth(true);
            StackPane holder = new StackPane(iv);
            holder.getStyleClass().add("viewer-thumb");
            double aspect = aspectOf(i);
            holder.setMinHeight(THUMB_W * aspect + 4);
            holder.setPrefHeight(THUMB_W * aspect + 4);
            Label num = new Label(String.valueOf(i + 1));
            num.getStyleClass().add("viewer-thumb-num");
            StackPane.setAlignment(num, Pos.BOTTOM_RIGHT);
            holder.getChildren().add(num);
            holder.setOnMouseClicked(e -> goTo(page));
            thumbHolders[i] = holder;
            thumbImages[i] = iv;
            thumbRail.getChildren().add(holder);
        }
        highlightThumb();
    }

    /** Height/width ratio of page {@code i} (defaults to A4-ish if unknown). */
    private double aspectOf(int i) {
        if (i < sizesPt.length && sizesPt[i][0] > 0) return sizesPt[i][1] / sizesPt[i][0];
        return 1.414;
    }

    /** Renders the thumbnails whose tiles sit within (or near) the rail's viewport. */
    private void renderVisibleThumbs() {
        if (thumbHolders.length == 0) return;
        Bounds vp = thumbViewportBounds();
        if (vp.getHeight() <= 0) return;            // not laid out yet
        long token = loadToken;
        for (int i = 0; i < thumbHolders.length; i++) {
            if (thumbRequested[i]) continue;
            Bounds b = thumbHolders[i].localToScene(thumbHolders[i].getBoundsInLocal());
            if (b.getHeight() <= 0) continue;
            if (b.getMaxY() < vp.getMinY() - THUMB_PREFETCH
                || b.getMinY() > vp.getMaxY() + THUMB_PREFETCH) continue;
            thumbRequested[i] = true;
            final int page = i;
            exec.submit(() -> {
                try {
                    if (source == null) return;
                    Image img = source.render(page, THUMB_DPI);
                    Platform.runLater(() -> {
                        if (token == loadToken && page < thumbImages.length && thumbImages[page] != null)
                            thumbImages[page].setImage(img);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        if (token == loadToken && page < thumbRequested.length)
                            thumbRequested[page] = false;   // let a later pass retry
                    });
                }
            });
        }
    }

    private Bounds thumbViewportBounds() {
        Node vp = thumbScroll.lookup(".viewport");
        Node ref = vp != null ? vp : thumbScroll;
        return ref.localToScene(ref.getLayoutBounds());
    }

    private void highlightThumb() {
        for (int i = 0; i < thumbHolders.length; i++) {
            thumbHolders[i].pseudoClassStateChanged(ACTIVE, i == pageIndex);
        }
    }

    private void scrollThumbIntoView() {
        highlightThumb();
        if (thumbHolders.length == 0 || pageCount <= 1) return;
        thumbScroll.setVvalue((double) pageIndex / Math.max(1, pageCount - 1));
        renderVisibleThumbs();
    }

    // --- control state ----------------------------------------------------

    private void updateControls() {
        boolean has = pageCount > 0;
        prevBtn.setDisable(!has || pageIndex <= 0);
        nextBtn.setDisable(!has || pageIndex >= pageCount - 1);
        for (Button b : new Button[]{zoomOutBtn, zoomInBtn, fitWidthBtn, fitPageBtn}) b.setDisable(!has);
        pageField.setDisable(!has);
        countLabel.setText(has ? I18n.t("preview.count", pageCount) : "");
        syncPageField();
        highlightThumb();
    }

    private void syncPageField() {
        pageField.setText(pageCount > 0 ? String.valueOf(pageIndex + 1) : "");
    }

    private void updateZoomLabel(double scale) {
        zoomLabel.setText(Math.round(scale * 100) + "%");
    }

    private void showEmpty(boolean empty) {
        placeholder.setVisible(empty);
        placeholder.setManaged(empty);
        pageView.setVisible(!empty);
    }

    private void retranslate() {
        updateControls();
        updateZoomLabel(lastScale);
    }

    private void tip(Button b, String key) {
        Tooltip t = new Tooltip();
        I18n.bindText(t::setText, key);
        b.setTooltip(t);
    }
}
