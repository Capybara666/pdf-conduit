package org.example.app.gui.component;

import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.example.app.i18n.I18n;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A grid of PDF page thumbnails the user toggles to build a <em>selection</em> of
 * pages (for Extract / Rotate). Click a tile to include or exclude its page; the
 * {@linkplain #selected() result} is the sorted set of selected page numbers.
 * Visuals are shared with {@link PageReorderGrid} via the {@code page-tile} styles.
 */
public final class PageSelectGrid extends ScrollPane {

    private static final double TILE_WIDTH = 116;
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    private final FlowPane flow = new FlowPane(14, 14);
    private final List<Cell> cells = new ArrayList<>();
    private Runnable onChange = () -> {};

    private final class Cell {
        final int page;          // 1-based source page
        final VBox node;
        boolean selected = true;

        Cell(int page, Image thumb) {
            this.page = page;
            ImageView view = new ImageView(thumb);
            view.setFitWidth(TILE_WIDTH);
            view.setPreserveRatio(true);
            view.setSmooth(true);
            StackPane thumbHolder = new StackPane(view);
            thumbHolder.getStyleClass().add("page-tile-thumb");

            Label check = new Label("✓");
            check.getStyleClass().add("page-tile-check");
            StackPane.setAlignment(check, Pos.TOP_RIGHT);
            StackPane.setMargin(check, new Insets(4));
            thumbHolder.getChildren().add(check);

            Label badge = new Label(I18n.t("arrange.tile.page", page));
            badge.getStyleClass().add("page-tile-badge");

            node = new VBox(6, thumbHolder, badge);
            node.getStyleClass().addAll("page-tile", "page-tile-selectable");
            node.setAlignment(Pos.CENTER);
            node.setPrefWidth(TILE_WIDTH + 16);
            node.setOnMouseClicked(e -> setSelected(!selected));
            apply();
        }

        void setSelected(boolean s) {
            if (selected == s) return;
            selected = s;
            apply();
            onChange.run();
        }

        private void apply() {
            node.pseudoClassStateChanged(SELECTED, selected);
        }
    }

    public PageSelectGrid() {
        getStyleClass().add("page-grid-scroll");
        setFitToWidth(true);
        flow.getStyleClass().add("page-grid");
        flow.setPadding(new Insets(14));
        flow.setAlignment(Pos.TOP_LEFT);
        setContent(flow);
    }

    /** Called whenever the selection changes. */
    public void setOnChange(Runnable r) { this.onChange = r == null ? () -> {} : r; }

    /** Populates the grid from page thumbnails (1-based, source order); all selected. */
    public void setPages(List<Image> thumbnails) {
        cells.clear();
        flow.getChildren().clear();
        for (int i = 0; i < thumbnails.size(); i++) {
            Cell c = new Cell(i + 1, thumbnails.get(i));
            cells.add(c);
            flow.getChildren().add(c.node);
        }
    }

    public int total() { return cells.size(); }

    /** Sorted list of selected page numbers (1-based). */
    public List<Integer> selected() {
        List<Integer> out = new ArrayList<>();
        for (Cell c : cells) if (c.selected) out.add(c.page);
        return out;
    }

    public int selectedCount() {
        int n = 0;
        for (Cell c : cells) if (c.selected) n++;
        return n;
    }

    /** Restricts the selection to {@code pages}; everything else is deselected. */
    public void setSelection(Set<Integer> pages) {
        for (Cell c : cells) c.setSelected(pages.contains(c.page));
    }

    public void selectAll()  { for (Cell c : cells) c.setSelected(true); }
    public void selectNone() { for (Cell c : cells) c.setSelected(false); }
    public void invert()     { for (Cell c : cells) c.setSelected(!c.selected); }
    public void selectOdd()  { for (Cell c : cells) c.setSelected(c.page % 2 == 1); }
    public void selectEven() { for (Cell c : cells) c.setSelected(c.page % 2 == 0); }
}
