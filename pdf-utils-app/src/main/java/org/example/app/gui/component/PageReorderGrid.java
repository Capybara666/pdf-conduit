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
    private double pressX, pressY;
    private boolean dragActive;
    private Runnable onChange = () -> {};

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
            moveGhost(e.getSceneX(), e.getSceneY());
            updateDropMarkers(e.getSceneX(), e.getSceneY());
        });

        cell.setOnMouseReleased(e -> {
            if (dragging != null && dragActive) dropAt(e.getSceneX(), e.getSceneY());
            clearAllMarkers();
            endGhost();
            if (draggingCell != null) draggingCell.setOpacity(1.0);
            dragging = null;
            draggingCell = null;
            dragActive = false;
        });
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
    }

    /** The flow child index whose bounds contain the scene point, or -1. */
    private int indexAt(double sceneX, double sceneY) {
        for (int i = 0; i < flow.getChildren().size(); i++) {
            Node n = flow.getChildren().get(i);
            if (n.localToScene(n.getBoundsInLocal()).contains(sceneX, sceneY)) return i;
        }
        return -1;
    }

    /** Shows a drop-before/after marker on the tile under the cursor (not the dragged one). */
    private void updateDropMarkers(double sceneX, double sceneY) {
        int idx = indexAt(sceneX, sceneY);
        for (int i = 0; i < flow.getChildren().size(); i++) {
            if (!(flow.getChildren().get(i) instanceof VBox c)) continue;
            if (i == idx && tiles.get(i) != dragging) {
                var b = c.localToScene(c.getBoundsInLocal());
                boolean after = sceneX >= b.getMinX() + b.getWidth() / 2;
                c.pseudoClassStateChanged(DROP_AFTER, after);
                c.pseudoClassStateChanged(DROP_BEFORE, !after);
            } else {
                clearMarkers(c);
            }
        }
    }

    /** Moves the dragged tile to where the cursor is (appends if over empty area). */
    private void dropAt(double sceneX, double sceneY) {
        int from = tiles.indexOf(dragging);
        if (from < 0) return;
        int idx = indexAt(sceneX, sceneY);
        int target;
        if (idx < 0) {
            target = tiles.size();                 // released over empty area → append
        } else {
            Node n = flow.getChildren().get(idx);
            var b = n.localToScene(n.getBoundsInLocal());
            target = idx + (sceneX >= b.getMinX() + b.getWidth() / 2 ? 1 : 0);
        }
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
