package com.pdfconduit.app.gui.component;

import javafx.collections.ObservableList;
import javafx.css.PseudoClass;
import javafx.scene.control.ListCell;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;

/**
 * Installs drag-and-drop row reordering on a {@link ListCell}, shared by every
 * reorderable list so they behave identically. Provides:
 * <ul>
 *   <li>a floating drag image (snapshot of the grabbed row) and a dimmed source cell,
 *       so it is clearly visible which row was picked up;</li>
 *   <li>a drop-line indicator that follows the cursor — a line above the hovered row
 *       when the cursor is in its top half, below it in the bottom half — driven by the
 *       {@code :drop-above} / {@code :drop-below} CSS pseudo-classes.</li>
 * </ul>
 */
public final class DragReorder {

    private static final PseudoClass DROP_ABOVE = PseudoClass.getPseudoClass("drop-above");
    private static final PseudoClass DROP_BELOW = PseudoClass.getPseudoClass("drop-below");

    private DragReorder() {}

    public static <T> void install(ListCell<T> cell, ObservableList<T> items) {
        cell.setOnDragDetected(e -> {
            if (cell.isEmpty() || cell.getItem() == null) return;
            Dragboard db = cell.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent cc = new ClipboardContent();
            cc.putString(String.valueOf(cell.getIndex()));
            db.setContent(cc);
            db.setDragView(cell.snapshot(null, null));
            cell.setOpacity(0.4);
            e.consume();
        });

        cell.setOnDragOver(e -> {
            if (e.getGestureSource() != cell && e.getDragboard().hasString()) {
                e.acceptTransferModes(TransferMode.MOVE);
                if (cell.isEmpty()) {
                    cell.pseudoClassStateChanged(DROP_ABOVE, true);
                    cell.pseudoClassStateChanged(DROP_BELOW, false);
                } else {
                    boolean below = e.getY() >= cell.getHeight() / 2;
                    cell.pseudoClassStateChanged(DROP_BELOW, below);
                    cell.pseudoClassStateChanged(DROP_ABOVE, !below);
                }
            }
            e.consume();
        });

        cell.setOnDragExited(e -> clearMarkers(cell));

        cell.setOnDragDropped(e -> {
            Dragboard db = e.getDragboard();
            if (!db.hasString()) { e.setDropCompleted(false); e.consume(); return; }
            int from = Integer.parseInt(db.getString());
            if (from < 0 || from >= items.size()) { e.setDropCompleted(false); e.consume(); return; }

            int target;
            if (cell.isEmpty()) {
                target = items.size();
            } else {
                int idx = cell.getIndex();
                target = (e.getY() >= cell.getHeight() / 2) ? idx + 1 : idx;
            }

            T moved = items.get(from);
            items.remove(from);
            if (target > from) target--;
            if (target < 0) target = 0;
            if (target > items.size()) target = items.size();
            items.add(target, moved);

            clearMarkers(cell);
            e.setDropCompleted(true);
            e.consume();
        });

        cell.setOnDragDone(e -> {
            cell.setOpacity(1.0);
            clearMarkers(cell);
        });
    }

    private static void clearMarkers(ListCell<?> cell) {
        cell.pseudoClassStateChanged(DROP_ABOVE, false);
        cell.pseudoClassStateChanged(DROP_BELOW, false);
    }
}
