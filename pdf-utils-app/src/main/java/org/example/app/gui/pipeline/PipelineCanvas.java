package org.example.app.gui.pipeline;

import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Pane;
import javafx.scene.shape.CubicCurve;
import org.example.core.pipeline.Connection;
import org.example.core.pipeline.NodeKind;
import org.example.core.pipeline.PipelineModel;
import org.example.core.pipeline.PipelineNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** The editing surface: hosts node cards and connection wires, handles gestures. */
class PipelineCanvas extends Pane {

    final PipelineModel model;
    private final Map<String, NodeView> nodeViews = new HashMap<>();
    private final List<ConnectionView> connViews = new ArrayList<>();

    private PipelineNode selected;
    private PipelineNode pendingFrom;
    private CubicCurve tempCurve;
    private NodeView hoverTarget;
    private Consumer<PipelineNode> onSelect = n -> {};
    private Runnable onChange = () -> {};
    private int idSeq = 0;

    PipelineCanvas(PipelineModel model) {
        this.model = model;
        getStyleClass().add("pipeline-canvas");
        setPrefSize(2400, 1500);
        setFocusTraversable(true);
        setOnMousePressed(e -> {
            if (e.getTarget() == this) selectNode(null);
            requestFocus();
        });
        setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE) deleteSelected();
        });
        // Re-translate the live nodes, wires and inspector in place — the graph
        // itself (positions, connections, options) is left untouched.
        org.example.app.i18n.I18n.addListener(this::relocalize);
    }

    private void relocalize() {
        nodeViews.values().forEach(NodeView::relocalize);
        connViews.forEach(ConnectionView::relocalize);
        onSelect.accept(selected);
    }

    void setOnSelect(Consumer<PipelineNode> onSelect) { this.onSelect = onSelect; }

    void setOnChange(Runnable onChange) { this.onChange = onChange == null ? () -> {} : onChange; }

    boolean isEmpty() { return model.nodes.isEmpty(); }

    String newId() { return "n" + (++idSeq); }

    PipelineNode addNode(NodeKind kind, double x, double y) {
        PipelineNode n = new PipelineNode(newId(), kind, x, y);
        model.nodes.add(n);
        NodeView v = new NodeView(n, this);
        nodeViews.put(n.id, v);
        getChildren().add(v);
        selectNode(n);
        onChange.run();
        return n;
    }

    void removeNode(PipelineNode n) {
        List<ConnectionView> touching = connViews.stream()
            .filter(cv -> cv.connection.fromNodeId().equals(n.id)
                       || cv.connection.toNodeId().equals(n.id))
            .toList();
        for (ConnectionView cv : touching) {
            getChildren().remove(cv);
            connViews.remove(cv);
        }
        model.removeNode(n.id);
        NodeView v = nodeViews.remove(n.id);
        if (v != null) getChildren().remove(v);
        if (selected == n) selectNode(null);
        refreshAll();
        onChange.run();
    }

    void selectNode(PipelineNode n) {
        selected = n;
        nodeViews.values().forEach(v -> v.setSelected(v.node == n));
        onSelect.accept(n);
        requestFocus();
    }

    private void deleteSelected() {
        if (selected != null) removeNode(selected);
    }

    void removeConnectionView(ConnectionView cv) {
        model.removeConnection(cv.connection);
        getChildren().remove(cv);
        connViews.remove(cv);
        refreshAll();
    }

    // --- connection gesture ----------------------------------------------

    void beginConnect(PipelineNode from) {
        pendingFrom = from;
        tempCurve = new CubicCurve();
        tempCurve.getStyleClass().add("pipeline-connection-temp");
        tempCurve.setFill(null);
        tempCurve.setMouseTransparent(true);   // never intercept the drop on the target port
        Point2D s = center(nodeViews.get(from.id).outPort());
        tempCurve.setStartX(s.getX()); tempCurve.setStartY(s.getY());
        tempCurve.setEndX(s.getX());   tempCurve.setEndY(s.getY());
        getChildren().add(tempCurve);
    }

    void updateTempEnd(double sceneX, double sceneY) {
        if (tempCurve == null) return;
        Point2D p = sceneToLocal(sceneX, sceneY);
        tempCurve.setEndX(p.getX()); tempCurve.setEndY(p.getY());
        double dx = Math.max(40, Math.abs(tempCurve.getEndX() - tempCurve.getStartX()) / 2);
        tempCurve.setControlX1(tempCurve.getStartX() + dx); tempCurve.setControlY1(tempCurve.getStartY());
        tempCurve.setControlX2(tempCurve.getEndX() - dx);   tempCurve.setControlY2(tempCurve.getEndY());

        // Live feedback: while over an input port, colour the wire and that port
        // green when the connection is allowed, red when it would be rejected.
        NodeView target = nearestInputTarget(sceneX, sceneY);
        boolean valid = target != null && canConnect(pendingFrom, target.node);
        boolean invalid = target != null && !valid;
        setHoverTarget(target, valid);
        toggleClass(tempCurve.getStyleClass(), "pipeline-wire-valid", valid);
        toggleClass(tempCurve.getStyleClass(), "pipeline-wire-invalid", invalid);
    }

    /** Completes a pending connection by hit-testing input ports near the cursor. */
    void finishConnectAt(double sceneX, double sceneY) {
        try {
            if (pendingFrom == null) return;
            NodeView target = nearestInputTarget(sceneX, sceneY);
            if (target != null && canConnect(pendingFrom, target.node)) {
                Connection conn = new Connection(pendingFrom.id, target.node.id);
                model.connections.add(conn);
                addConnectionView(conn);
                refreshAll();
            }
        } finally {
            cancelConnect();
        }
    }

    /** The node whose input port is nearest the cursor (within ~26px), or null. */
    private NodeView nearestInputTarget(double sceneX, double sceneY) {
        NodeView best = null;
        double bestDist = Double.MAX_VALUE;
        for (NodeView v : nodeViews.values()) {
            if (v.node.kind.isSource()) continue;          // sources have no input
            var b = v.inPort().getBoundsInLocal();
            Point2D c = v.inPort().localToScene((b.getMinX() + b.getMaxX()) / 2,
                                                (b.getMinY() + b.getMaxY()) / 2);
            double d = Math.hypot(c.getX() - sceneX, c.getY() - sceneY);
            if (d <= 26 && d < bestDist) { bestDist = d; best = v; }
        }
        return best;
    }

    private void setHoverTarget(NodeView target, boolean valid) {
        if (hoverTarget != null && hoverTarget != target) {
            hoverTarget.setInPortValid(false);
            hoverTarget.setInPortInvalid(false);
        }
        hoverTarget = target;
        if (hoverTarget != null) {
            hoverTarget.setInPortValid(valid);
            hoverTarget.setInPortInvalid(!valid);
        }
    }

    private static void toggleClass(List<String> classes, String name, boolean on) {
        if (on) { if (!classes.contains(name)) classes.add(name); }
        else classes.remove(name);
    }

    void cancelConnect() {
        if (tempCurve != null) {
            getChildren().remove(tempCurve);
            tempCurve = null;
        }
        setHoverTarget(null, false);
        pendingFrom = null;
    }

    private boolean canConnect(PipelineNode from, PipelineNode to) {
        if (from == to || to.kind.isSource()) return false;
        if (model.connected(from.id, to.id)) return false;
        return !reaches(to.id, from.id);   // adding from→to must not close a cycle
    }

    private boolean reaches(String start, String target) {
        Deque<String> stack = new ArrayDeque<>();
        stack.push(start);
        Set<String> seen = new HashSet<>();
        while (!stack.isEmpty()) {
            String cur = stack.pop();
            if (cur.equals(target)) return true;
            if (!seen.add(cur)) continue;
            for (Connection c : model.outgoing(cur)) stack.push(c.toNodeId());
        }
        return false;
    }

    private void addConnectionView(Connection c) {
        NodeView fv = nodeViews.get(c.fromNodeId());
        NodeView tv = nodeViews.get(c.toNodeId());
        ConnectionView cv = new ConnectionView(c, fv, tv, fv.outPort(), tv.inPort(), this);
        cv.setOnDelete(() -> removeConnectionView(cv));
        connViews.add(cv);
        getChildren().add(0, cv);   // keep wires behind node cards
    }

    private Point2D center(Node port) {
        var b = port.getBoundsInLocal();
        return sceneToLocal(port.localToScene((b.getMinX() + b.getMaxX()) / 2,
                                              (b.getMinY() + b.getMaxY()) / 2));
    }

    void clearAll() {
        getChildren().clear();
        nodeViews.clear();
        connViews.clear();
        model.nodes.clear();
        model.connections.clear();
        selected = null;
        onSelect.accept(null);
        onChange.run();
    }

    /** Refresh node summaries, redraw wires, and re-show the inspector (terminal state may change). */
    void refreshAll() {
        nodeViews.values().forEach(NodeView::refreshSummary);
        connViews.forEach(ConnectionView::update);
        onSelect.accept(selected);
    }

    /** Lightweight refresh of one node's summary + wires, without re-showing the inspector. */
    void refreshNode(PipelineNode n) {
        NodeView v = nodeViews.get(n.id);
        if (v != null) v.refreshSummary();
        connViews.forEach(ConnectionView::update);
    }

    void highlightErrors(Set<String> nodeIds) {
        nodeViews.values().forEach(v -> v.setError(nodeIds.contains(v.node.id)));
    }

    Collection<NodeView> views() { return nodeViews.values(); }
}
