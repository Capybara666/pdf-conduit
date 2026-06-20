package org.example.app.gui.component;

import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
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
    private final ObservableList<Tile> tiles = FXCollections.observableArrayList();
    private List<Tile> original = List.of();
    private Tile dragging;
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
        setContent(flow);

        tiles.addListener((ListChangeListener<Tile>) c -> { relayout(); onChange.run(); });

        // Re-render the tiles (badges + tooltips) in the new language, keeping order.
        I18n.addListener(this::relayout);

        // Dropping onto empty pane area appends to the end.
        flow.setOnDragOver(e -> {
            if (dragging != null) e.acceptTransferModes(TransferMode.MOVE);
            e.consume();
        });
        flow.setOnDragDropped(e -> {
            if (dragging != null) {
                tiles.remove(dragging);
                tiles.add(dragging);
                e.setDropCompleted(true);
            }
            e.consume();
        });
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

    // --- drag-and-drop reordering -----------------------------------------

    private void installDrag(VBox cell, Tile tile) {
        // Start the drag only after a deliberate movement, not on a plain click.
        // Relying on onDragDetected (which fires after ~1px) meant an ordinary
        // click started — and on Linux could leave hanging — a drag-and-drop
        // gesture, which suppresses the ScrollPane's wheel scrolling until the
        // gesture is cleared by another click. A movement threshold makes a click
        // stay a click, so scrolling keeps working.
        final double[] press = new double[2];
        final boolean[] started = {false};

        cell.setOnMousePressed(e -> {
            press[0] = e.getX();
            press[1] = e.getY();
            started[0] = false;
        });

        cell.setOnMouseDragged(e -> {
            if (started[0]) return;
            double dx = e.getX() - press[0];
            double dy = e.getY() - press[1];
            if (dx * dx + dy * dy < DRAG_THRESHOLD * DRAG_THRESHOLD) return;
            started[0] = true;
            dragging = tile;
            Dragboard db = cell.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent cc = new ClipboardContent();
            cc.putString(String.valueOf(tile.sourcePage));   // payload required, value unused
            db.setContent(cc);
            db.setDragView(cell.snapshot(null, null));
            cell.setOpacity(0.4);
            e.consume();
        });

        cell.setOnDragOver(e -> {
            if (dragging != null && dragging != tile) {
                e.acceptTransferModes(TransferMode.MOVE);
                boolean after = e.getX() >= cell.getWidth() / 2;
                cell.pseudoClassStateChanged(DROP_AFTER, after);
                cell.pseudoClassStateChanged(DROP_BEFORE, !after);
            }
            e.consume();
        });

        cell.setOnDragExited(e -> clearMarkers(cell));

        cell.setOnDragDropped(e -> {
            if (dragging == null) { e.setDropCompleted(false); e.consume(); return; }
            int from = tiles.indexOf(dragging);
            int target = tiles.indexOf(tile) + (e.getX() >= cell.getWidth() / 2 ? 1 : 0);
            tiles.remove(from);
            if (from < target) target--;          // removal shifted later items left
            target = Math.max(0, Math.min(target, tiles.size()));
            tiles.add(target, dragging);
            clearMarkers(cell);
            e.setDropCompleted(true);
            e.consume();
        });

        cell.setOnDragDone(e -> {
            cell.setOpacity(1.0);
            clearMarkers(cell);
            dragging = null;
        });
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
