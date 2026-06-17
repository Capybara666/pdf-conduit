package org.example.app.pipeline;

import java.util.ArrayList;
import java.util.List;

/** The pipeline graph: nodes plus directed connections between them. */
public class PipelineModel {

    public final List<PipelineNode> nodes = new ArrayList<>();
    public final List<Connection> connections = new ArrayList<>();

    public PipelineNode node(String id) {
        for (PipelineNode n : nodes) {
            if (n.id.equals(id)) return n;
        }
        return null;
    }

    /** Connections entering {@code nodeId}, in stable input order. */
    public List<Connection> incoming(String nodeId) {
        List<Connection> in = new ArrayList<>();
        for (Connection c : connections) {
            if (c.toNodeId().equals(nodeId)) in.add(c);
        }
        return in;
    }

    public List<Connection> outgoing(String nodeId) {
        List<Connection> out = new ArrayList<>();
        for (Connection c : connections) {
            if (c.fromNodeId().equals(nodeId)) out.add(c);
        }
        return out;
    }

    /** A node that produces a result to save: an operation with no outgoing edge. */
    public boolean isTerminal(PipelineNode n) {
        return !n.kind.isSource() && outgoing(n.id).isEmpty();
    }

    public boolean connected(String fromId, String toId) {
        return connections.stream()
            .anyMatch(c -> c.fromNodeId().equals(fromId) && c.toNodeId().equals(toId));
    }

    public void removeNode(String id) {
        nodes.removeIf(n -> n.id.equals(id));
        connections.removeIf(c -> c.fromNodeId().equals(id) || c.toNodeId().equals(id));
    }

    public void removeConnection(Connection c) {
        connections.remove(c);
    }
}
