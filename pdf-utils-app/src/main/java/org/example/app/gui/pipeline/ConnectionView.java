package org.example.app.gui.pipeline;

import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.shape.CubicCurve;
import org.example.app.pipeline.Connection;

/** A bezier wire between an output port and an input port; follows node movement. */
class ConnectionView extends CubicCurve {

    final Connection connection;
    private final Node fromPort;
    private final Node toPort;
    private final Pane canvas;

    ConnectionView(Connection connection, Region fromView, Region toView,
                   Node fromPort, Node toPort, Pane canvas) {
        this.connection = connection;
        this.fromPort = fromPort;
        this.toPort = toPort;
        this.canvas = canvas;

        getStyleClass().add("pipeline-connection");
        setFill(null);

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
            if (!getStyleClass().contains("selected")) getStyleClass().add("selected");
        } else {
            getStyleClass().remove("selected");
        }
    }

    void update() {
        Point2D f = center(fromPort);
        Point2D t = center(toPort);
        setStartX(f.getX()); setStartY(f.getY());
        setEndX(t.getX());   setEndY(t.getY());
        double dx = Math.max(40, Math.abs(t.getX() - f.getX()) / 2);
        setControlX1(f.getX() + dx); setControlY1(f.getY());
        setControlX2(t.getX() - dx); setControlY2(t.getY());
    }

    private Point2D center(Node port) {
        var b = port.getBoundsInLocal();
        Point2D scene = port.localToScene((b.getMinX() + b.getMaxX()) / 2,
                                          (b.getMinY() + b.getMaxY()) / 2);
        return canvas.sceneToLocal(scene);
    }
}
