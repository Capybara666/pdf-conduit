package org.example.app.gui.pipeline;

import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.CubicCurve;
import org.example.app.pipeline.Connection;

/**
 * A bezier wire between an output port and an input port; follows node movement.
 *
 * <p>Rendered as two overlaid curves: a thin visible {@code wire} and a thick
 * transparent {@code hit} curve that widens the clickable area so the wire is
 * easy to select (and then delete). Hovering shows it is interactive.
 */
class ConnectionView extends Group {

    final Connection connection;
    private final CubicCurve wire = new CubicCurve();
    private final CubicCurve hit = new CubicCurve();
    private final Node fromPort;
    private final Node toPort;
    private final Pane canvas;

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
        hit.setStrokeWidth(14);
        hit.setCursor(Cursor.HAND);

        getChildren().addAll(hit, wire);

        setOnMouseEntered(e -> {
            if (!wire.getStyleClass().contains("hover")) wire.getStyleClass().add("hover");
        });
        setOnMouseExited(e -> wire.getStyleClass().remove("hover"));

        Runnable update = this::update;
        for (Region v : new Region[]{fromView, toView}) {
            v.layoutXProperty().addListener((o, a, b) -> update.run());
            v.layoutYProperty().addListener((o, a, b) -> update.run());
            v.boundsInParentProperty().addListener((o, a, b) -> update.run());
        }
        update();
    }

    void setSelected(boolean selected) {
        if (selected) {
            if (!wire.getStyleClass().contains("selected")) wire.getStyleClass().add("selected");
        } else {
            wire.getStyleClass().remove("selected");
        }
    }

    void update() {
        Point2D f = center(fromPort);
        Point2D t = center(toPort);
        double dx = Math.max(40, Math.abs(t.getX() - f.getX()) / 2);
        for (CubicCurve c : new CubicCurve[]{wire, hit}) {
            c.setStartX(f.getX()); c.setStartY(f.getY());
            c.setEndX(t.getX());   c.setEndY(t.getY());
            c.setControlX1(f.getX() + dx); c.setControlY1(f.getY());
            c.setControlX2(t.getX() - dx); c.setControlY2(t.getY());
        }
    }

    private Point2D center(Node port) {
        var b = port.getBoundsInLocal();
        Point2D scene = port.localToScene((b.getMinX() + b.getMaxX()) / 2,
                                          (b.getMinY() + b.getMaxY()) / 2);
        return canvas.sceneToLocal(scene);
    }
}
