package org.example.app.gui.pipeline;

import javafx.animation.PauseTransition;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.CubicCurve;
import javafx.util.Duration;
import org.example.app.i18n.I18n;
import org.example.app.pipeline.Connection;

/**
 * A bezier wire between an output port and an input port; follows node movement.
 *
 * <p>Hovering the wire reveals a small ✕ button floating just off the middle of
 * the cable. Hovering that button turns the cable red (a delete preview);
 * clicking it removes the connection. A wide transparent "hit" curve makes the
 * thin wire easy to hover.
 */
class ConnectionView extends Group {

    private static final double DELETE_OFFSET = 16;   // how far the ✕ sits off the cable

    final Connection connection;
    private final CubicCurve wire = new CubicCurve();
    private final CubicCurve hit = new CubicCurve();
    private final Label deleteBtn = new Label("✕");
    private final PauseTransition hideDelay = new PauseTransition(Duration.millis(140));
    private final Node fromPort;
    private final Node toPort;
    private final Pane canvas;
    private Runnable onDelete = () -> {};

    ConnectionView(Connection connection, Region fromView, Region toView,
                   Node fromPort, Node toPort, Pane canvas) {
        this.connection = connection;
        this.fromPort = fromPort;
        this.toPort = toPort;
        this.canvas = canvas;

        wire.getStyleClass().add("pipeline-connection");
        wire.setFill(null);

        hit.setFill(null);
        hit.setStroke(Color.TRANSPARENT);
        hit.setStrokeWidth(16);

        deleteBtn.getStyleClass().add("pipeline-connection-delete");
        deleteBtn.setManaged(false);
        deleteBtn.setVisible(false);
        deleteBtn.setCursor(Cursor.HAND);
        deleteBtn.setTooltip(new Tooltip(I18n.t("pipeline.connection.delete")));

        getChildren().addAll(hit, wire, deleteBtn);

        // Reveal the ✕ while hovering the cable or the button; hide after a short
        // grace period so moving from the cable to the (offset) button doesn't
        // make it vanish mid-reach.
        hideDelay.setOnFinished(e -> deleteBtn.setVisible(false));
        hit.setOnMouseEntered(e -> showDelete());
        hit.setOnMouseExited(e -> hideDelay.playFromStart());
        deleteBtn.setOnMouseEntered(e -> { showDelete(); setPendingDelete(true); });
        deleteBtn.setOnMouseExited(e -> { setPendingDelete(false); hideDelay.playFromStart(); });
        deleteBtn.setOnMouseClicked(e -> { onDelete.run(); e.consume(); });

        Runnable update = this::update;
        for (Region v : new Region[]{fromView, toView}) {
            v.layoutXProperty().addListener((o, a, b) -> update.run());
            v.layoutYProperty().addListener((o, a, b) -> update.run());
            v.boundsInParentProperty().addListener((o, a, b) -> update.run());
        }
        update();
    }

    void setOnDelete(Runnable onDelete) {
        this.onDelete = onDelete == null ? () -> {} : onDelete;
    }

    private void showDelete() {
        hideDelay.stop();
        deleteBtn.setVisible(true);
    }

    private void setPendingDelete(boolean pending) {
        if (pending) {
            if (!wire.getStyleClass().contains("pending-delete")) wire.getStyleClass().add("pending-delete");
        } else {
            wire.getStyleClass().remove("pending-delete");
        }
    }

    void update() {
        Point2D f = center(fromPort);
        Point2D t = center(toPort);
        double dx = Math.max(40, Math.abs(t.getX() - f.getX()) / 2);
        double c1x = f.getX() + dx, c2x = t.getX() - dx;
        for (CubicCurve c : new CubicCurve[]{wire, hit}) {
            c.setStartX(f.getX()); c.setStartY(f.getY());
            c.setEndX(t.getX());   c.setEndY(t.getY());
            c.setControlX1(c1x); c.setControlY1(f.getY());
            c.setControlX2(c2x); c.setControlY2(t.getY());
        }
        // Cubic point at t=0.5: (P0 + 3P1 + 3P2 + P3) / 8.
        double midX = (f.getX() + 3 * c1x + 3 * c2x + t.getX()) / 8;
        double midY = (f.getY() + 3 * f.getY() + 3 * t.getY() + t.getY()) / 8;
        deleteBtn.autosize();
        deleteBtn.relocate(midX - deleteBtn.getWidth() / 2,
                           midY - DELETE_OFFSET - deleteBtn.getHeight() / 2);
    }

    private Point2D center(Node port) {
        var b = port.getBoundsInLocal();
        Point2D scene = port.localToScene((b.getMinX() + b.getMaxX()) / 2,
                                          (b.getMinY() + b.getMaxY()) / 2);
        return canvas.sceneToLocal(scene);
    }
}
