package org.example.app.gui.component;

import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.Node;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.example.app.i18n.I18n;

import java.util.ArrayList;
import java.util.List;

/**
 * A visual, drag-to-reorder grid of PDF page thumbnails. Each tile remembers the
 * source page it came from; the {@linkplain #order() resulting order} is the list
 * of source page numbers in their current visual sequence. Tiles can be dragged to
 * reorder, removed, or duplicated — so the grid expresses any arrangement (move,
 * drop, repeat) the user wants.
 */
public final class PageReorderGrid extends ScrollPane {

    private static final double TILE_WIDTH = 116;

    /** Pointer travel (px) before a press becomes a drag — keeps a click a click. */
    private static final double DRAG_THRESHOLD = 8;

    private final FlowPane flow = new FlowPane(14, 14);
    // Sits above the tiles to carry the floating "page I'm dragging" thumbnail.
    private final Pane overlay = new Pane();
    private final ObservableList<Tile> tiles = FXCollections.observableArrayList();
    private List<Tile> original = List.of();
    private Tile dragging;
    private VBox draggingCell;
    private ImageView ghost;
    private javafx.scene.layout.Region dropLine;
    private double pressX, pressY;
    private boolean dragActive;
    // Last cursor position (scene coords) during a drag, so auto-scroll can re-track
    // the ghost and insertion marker against the (still) pointer each frame.
    private double lastSceneX, lastSceneY;
    private double autoScrollSpeed;
    private javafx.animation.AnimationTimer autoScroller;
    private Runnable onChange = () -> {};

    /** How deep (px) into the top/bottom of the viewport a drag must reach to auto-scroll. */
    private static final double EDGE_MARGIN = 60;
    /** Peak auto-scroll speed in pixels per frame, at the very edge of the viewport. */
    private static final double MAX_SCROLL_PX = 16;

    /** One page in the grid: the 1-based source page number and its thumbnail. */
    private static final class Tile {
        final int sourcePage;
        final Image thumb;
        Tile(int sourcePage, Image thumb) { this.sourcePage = sourcePage; this.thumb = thumb; }
    }

    public PageReorderGrid() {
        getStyleClass().add("page-grid-scroll");
        setFitToWidth(true);
        flow.getStyleClass().add("page-grid");
        flow.setPadding(new Insets(14));
        flow.setAlignment(Pos.TOP_LEFT);
        // The overlay must let clicks/drags fall through to the tiles beneath it.
        overlay.setMouseTransparent(true);
        StackPane content = new StackPane(flow, overlay);
        content.setAlignment(Pos.TOP_LEFT);
        setContent(content);

        // Drive wheel scrolling explicitly so it stays consistent regardless of
        // focus/gesture state (handled in the capturing phase, before the skin).
        addEventFilter(ScrollEvent.SCROLL, e -> {
            double extent = flow.getBoundsInLocal().getHeight() - getViewportBounds().getHeight();
            if (extent <= 0 || e.getDeltaY() == 0) return;
            double v = getVvalue() - e.getDeltaY() / extent;
            setVvalue(Math.max(0, Math.min(1, v)));
            e.consume();
        });

        tiles.addListener((ListChangeListener<Tile>) c -> { relayout(); onChange.run(); });

        // While a drag rests near the top/bottom edge, scroll the list towards it
        // (the wheel can't be delivered while a button is held), re-tracking the
        // ghost + marker against the still cursor each frame.
        autoScroller = new javafx.animation.AnimationTimer() {
            @Override public void handle(long now) {
                if (!dragActive || autoScrollSpeed == 0) { stop(); return; }
                double extent = flow.getBoundsInLocal().getHeight() - getViewportBounds().getHeight();
                if (extent <= 0) { stop(); return; }
                setVvalue(Math.max(0, Math.min(1, getVvalue() + autoScrollSpeed / extent)));
                updateDuringDrag(lastSceneX, lastSceneY);
            }
        };

        // Re-render the tiles (badges + tooltips) in the new language, keeping order.
        I18n.addListener(this::relayout);
    }

    /** Called whenever the arrangement changes (reorder, remove, duplicate, reset). */
    public void setOnChange(Runnable r) { this.onChange = r == null ? () -> {} : r; }

    /** Populates the grid from page thumbnails; pages are 1-based in source order. */
    public void setPages(List<Image> thumbnails) {
        List<Tile> built = new ArrayList<>(thumbnails.size());
        for (int i = 0; i < thumbnails.size(); i++) built.add(new Tile(i + 1, thumbnails.get(i)));
        original = List.copyOf(built);
        tiles.setAll(built);
    }

    /** Restores every page to its original position. */
    public void reset() { tiles.setAll(original); }

    /** Reverses the current order. */
    public void reverse() {
        List<Tile> copy = new ArrayList<>(tiles);
        java.util.Collections.reverse(copy);
        tiles.setAll(copy);
    }

    public void clear() { original = List.of(); tiles.clear(); }

    public boolean isEmpty() { return tiles.isEmpty(); }

    public int pageCount() { return tiles.size(); }

    /** The arrangement as 1-based source page numbers in current visual order. */
    public List<Integer> order() {
        List<Integer> out = new ArrayList<>(tiles.size());
        for (Tile t : tiles) out.add(t.sourcePage);
        return out;
    }

    // --- rendering --------------------------------------------------------

    private void relayout() {
        flow.getChildren().clear();
        for (int i = 0; i < tiles.size(); i++) {
            flow.getChildren().add(buildCell(tiles.get(i), i + 1));
        }
    }

    private VBox buildCell(Tile tile, int positionLabel) {
        ImageView view = new ImageView(tile.thumb);
        view.setFitWidth(TILE_WIDTH);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        StackPane thumbHolder = new StackPane(view);
        thumbHolder.getStyleClass().add("page-tile-thumb");

        Button remove = iconButton("✕", I18n.t("arrange.tile.remove"));
        remove.setOnAction(e -> tiles.remove(tile));
        Button duplicate = iconButton("⧉", I18n.t("arrange.tile.duplicate"));
        duplicate.setOnAction(e -> {
            int idx = tiles.indexOf(tile);
            tiles.add(idx + 1, new Tile(tile.sourcePage, tile.thumb));
        });
        var spacer = new javafx.scene.layout.Region();
        javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        javafx.scene.layout.HBox controls =
            new javafx.scene.layout.HBox(4, duplicate, spacer, remove);
        controls.getStyleClass().add("page-tile-controls");
        controls.setAlignment(Pos.CENTER);
        StackPane.setAlignment(controls, Pos.TOP_CENTER);
        thumbHolder.getChildren().add(controls);

        Label badge = new Label(I18n.t("arrange.tile.page", tile.sourcePage));
        badge.getStyleClass().add("page-tile-badge");

        VBox cell = new VBox(6, thumbHolder, badge);
        cell.getStyleClass().add("page-tile");
        cell.setAlignment(Pos.CENTER);
        cell.setPrefWidth(TILE_WIDTH + 16);
        installDrag(cell, tile);
        return cell;
    }

    private Button iconButton(String glyph, String tip) {
        Button b = new Button(glyph);
        b.getStyleClass().add("page-tile-btn");
        b.setFocusTraversable(false);
        b.setTooltip(new Tooltip(tip));
        return b;
    }

    // --- reordering (plain mouse events) ----------------------------------
    //
    // Deliberately NOT JavaFX's startDragAndDrop: on Linux/GTK a native drag
    // gesture leaves the ScrollPane unable to deliver wheel-scroll events until
    // the next click. Reordering is done with press/drag/release instead, hit-
    // testing tiles by scene coordinates, so no native drag gesture is involved.
    // A floating thumbnail (the "ghost") follows the cursor — drawn in an overlay
    // Pane rather than by the native drag-view, which would re-introduce the bug.

    private void installDrag(VBox cell, Tile tile) {
        cell.setOnMousePressed(e -> {
            dragging = tile;
            draggingCell = cell;
            pressX = e.getSceneX();
            pressY = e.getSceneY();
            dragActive = false;
        });

        cell.setOnMouseDragged(e -> {
            if (dragging == null) return;
            if (!dragActive) {
                double dx = e.getSceneX() - pressX, dy = e.getSceneY() - pressY;
                if (dx * dx + dy * dy < DRAG_THRESHOLD * DRAG_THRESHOLD) return;
                dragActive = true;
                startGhost(cell);        // snapshot at full opacity, before dimming
                cell.setOpacity(0.4);
            }
            lastSceneX = e.getSceneX();
            lastSceneY = e.getSceneY();
            updateDuringDrag(lastSceneX, lastSceneY);
            updateAutoScroll(lastSceneY);
        });

        cell.setOnMouseReleased(e -> {
            if (dragging != null && dragActive) dropAt(e.getSceneX(), e.getSceneY());
            stopAutoScroll();
            clearAllMarkers();
            endGhost();
            if (draggingCell != null) draggingCell.setOpacity(1.0);
            dragging = null;
            draggingCell = null;
            dragActive = false;
        });
    }

    // --- live drag tracking + edge auto-scroll ----------------------------

    /** Repositions the ghost and refreshes the insertion marker for a cursor point. */
    private void updateDuringDrag(double sceneX, double sceneY) {
        moveGhost(sceneX, sceneY);
        updateDropMarkers(sceneX, sceneY);
    }

    /** The actual visible viewport rectangle in scene coordinates. */
    private javafx.geometry.Bounds viewportSceneBounds() {
        Node vp = lookup(".viewport");
        Node ref = vp != null ? vp : this;
        return ref.localToScene(ref.getLayoutBounds());
    }

    /**
     * Sets the auto-scroll speed from how deep the cursor sits in the viewport's
     * top/bottom band. Symmetric for up and down, and only while the cursor is
     * actually inside the viewport — so a pointer in the middle never scrolls.
     */
    private void updateAutoScroll(double sceneY) {
        var vp = viewportSceneBounds();
        double speed = 0;
        if (sceneY >= vp.getMinY() && sceneY < vp.getMinY() + EDGE_MARGIN) {
            speed = -MAX_SCROLL_PX * (vp.getMinY() + EDGE_MARGIN - sceneY) / EDGE_MARGIN;
        } else if (sceneY <= vp.getMaxY() && sceneY > vp.getMaxY() - EDGE_MARGIN) {
            speed = MAX_SCROLL_PX * (sceneY - (vp.getMaxY() - EDGE_MARGIN)) / EDGE_MARGIN;
        }
        autoScrollSpeed = speed;
        if (speed != 0) autoScroller.start(); else autoScroller.stop();
    }

    private void stopAutoScroll() {
        autoScrollSpeed = 0;
        autoScroller.stop();
    }

    // --- floating drag-view (ghost) ---------------------------------------

    private void startGhost(VBox cell) {
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        ghost = new ImageView(cell.snapshot(params, null));
        ghost.getStyleClass().add("page-drag-ghost");
        ghost.setMouseTransparent(true);
        overlay.getChildren().setAll(ghost);
    }

    private void moveGhost(double sceneX, double sceneY) {
        if (ghost == null) return;
        // Anchor the preview's top-left at the cursor, like the native drag-view.
        Point2D p = overlay.sceneToLocal(sceneX, sceneY);
        ghost.relocate(p.getX(), p.getY());
    }

    private void endGhost() {
        overlay.getChildren().clear();
        ghost = null;
        dropLine = null;
    }

    /** Thickness (px) of the vertical insertion line — matches the edge marker's border. */
    private static final double LINE_W = 3;

    /** Draws the insertion line in the gap between two same-row tiles (scene bounds). */
    private void showDropLine(javafx.geometry.Bounds left, javafx.geometry.Bounds right) {
        if (dropLine == null) {
            dropLine = new javafx.scene.layout.Region();
            dropLine.getStyleClass().add("page-drop-line");
            dropLine.setMouseTransparent(true);
        }
        if (!overlay.getChildren().contains(dropLine)) overlay.getChildren().add(dropLine);
        double centerX = (left.getMaxX() + right.getMinX()) / 2;
        Point2D p = overlay.sceneToLocal(centerX, right.getMinY());
        dropLine.setPrefSize(LINE_W, right.getHeight());
        dropLine.relocate(p.getX() - LINE_W / 2, p.getY());
        dropLine.setVisible(true);
    }

    private void hideDropLine() {
        if (dropLine != null) dropLine.setVisible(false);
    }

    private static boolean sameRow(javafx.geometry.Bounds a, javafx.geometry.Bounds b) {
        return a.getMinY() < b.getMaxY() && b.getMinY() < a.getMaxY();
    }

    /**
     * The insertion index (0..size) for the cursor, in row-major reading order.
     * Unlike a plain bounds hit-test this also resolves the gaps *between* tiles:
     * a cursor in the gap inserts between its neighbours instead of falling
     * through to "append at the end". Returns size only when the cursor is truly
     * past the last tile (e.g. the empty area below the grid).
     */
    private int dropIndex(double sceneX, double sceneY) {
        var children = flow.getChildren();
        for (int i = 0; i < children.size(); i++) {
            var b = children.get(i).localToScene(children.get(i).getBoundsInLocal());
            if (sceneY < b.getMinY()) return i;            // cursor is in a row above tile i
            if (sceneY <= b.getMaxY()                       // cursor shares tile i's row…
                && sceneX < b.getMinX() + b.getWidth() / 2) // …and is left of its centre
                return i;
        }
        return children.size();                             // past the last tile → append
    }

    /**
     * Marks the cursor's insertion point. The true ends of the list (before the
     * first tile / after the last) light up that tile's edge; an insertion
     * between two tiles draws a vertical line in the gap instead.
     */
    private void updateDropMarkers(double sceneX, double sceneY) {
        clearAllMarkers();
        hideDropLine();
        var children = flow.getChildren();
        int n = children.size();
        if (n == 0) return;
        int from = tiles.indexOf(dragging);
        int target = dropIndex(sceneX, sceneY);
        if (target == from || target == from + 1) return;   // drop here = no move

        if (target == 0) {
            if (children.getFirst() instanceof VBox c) c.pseudoClassStateChanged(DROP_BEFORE, true);
        } else if (target == n) {
            if (children.get(n - 1) instanceof VBox c) c.pseudoClassStateChanged(DROP_AFTER, true);
        } else {
            var left = children.get(target - 1).localToScene(children.get(target - 1).getBoundsInLocal());
            var right = children.get(target).localToScene(children.get(target).getBoundsInLocal());
            if (sameRow(left, right)) {
                showDropLine(left, right);                    // between two tiles on a row
            } else if (sceneY <= left.getMaxY()) {            // wrap: cursor still in the left row…
                if (children.get(target - 1) instanceof VBox c)
                    c.pseudoClassStateChanged(DROP_AFTER, true);   // …so it's that row's right end
            } else if (children.get(target) instanceof VBox c) {
                c.pseudoClassStateChanged(DROP_BEFORE, true); // otherwise the next row's left start
            }
        }
    }

    /** Moves the dragged tile to the cursor's insertion point. */
    private void dropAt(double sceneX, double sceneY) {
        int from = tiles.indexOf(dragging);
        if (from < 0) return;
        int target = dropIndex(sceneX, sceneY);
        tiles.remove(from);
        if (from < target) target--;               // removal shifted later items left
        target = Math.min(target, tiles.size());
        tiles.add(target, dragging);
    }

    private void clearAllMarkers() {
        for (Node n : flow.getChildren()) {
            if (n instanceof VBox c) clearMarkers(c);
        }
    }

    private static final javafx.css.PseudoClass DROP_BEFORE =
        javafx.css.PseudoClass.getPseudoClass("drop-before");
    private static final javafx.css.PseudoClass DROP_AFTER =
        javafx.css.PseudoClass.getPseudoClass("drop-after");

    private static void clearMarkers(VBox cell) {
        cell.pseudoClassStateChanged(DROP_BEFORE, false);
        cell.pseudoClassStateChanged(DROP_AFTER, false);
    }
}
