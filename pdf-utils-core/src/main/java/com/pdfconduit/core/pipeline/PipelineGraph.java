package com.pdfconduit.core.pipeline;

import com.pdfconduit.core.pipeline.Document.DocType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static (no-execution) analysis of a {@link PipelineModel}: topological order
 * and propagation of each node's output document types. Used by both validation
 * and the GUI (to know whether a terminal yields one file or many).
 */
public final class PipelineGraph {

    private PipelineGraph() {}

    /** Thrown when the graph contains a cycle and cannot be ordered. */
    public static class CycleException extends Exception {
        public CycleException() { super("Pipeline contains a cycle."); }
    }

    /** Kahn's algorithm. Throws {@link CycleException} if not a DAG. */
    public static List<PipelineNode> topologicalOrder(PipelineModel model) throws CycleException {
        Map<String, Integer> indegree = new HashMap<>();
        for (PipelineNode n : model.nodes) indegree.put(n.id, 0);
        for (Connection c : model.connections) {
            indegree.merge(c.toNodeId(), 1, Integer::sum);
        }
        Deque<PipelineNode> ready = new ArrayDeque<>();
        for (PipelineNode n : model.nodes) {
            if (indegree.get(n.id) == 0) ready.add(n);
        }
        List<PipelineNode> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            PipelineNode n = ready.poll();
            order.add(n);
            for (Connection c : model.outgoing(n.id)) {
                int d = indegree.merge(c.toNodeId(), -1, Integer::sum);
                if (d == 0) ready.add(model.node(c.toNodeId()));
            }
        }
        if (order.size() != model.nodes.size()) throw new CycleException();
        return order;
    }

    /**
     * Output document types per node id (the list size is the output document
     * count). Source = file types; map = all PDF, one per input document; reduce
     * = a single PDF.
     */
    public static Map<String, List<DocType>> outputTypes(PipelineModel model) throws CycleException {
        Map<String, List<DocType>> out = new LinkedHashMap<>();
        for (PipelineNode n : topologicalOrder(model)) {
            List<DocType> bundle = new ArrayList<>();
            if (n.kind.isSource()) {
                for (var f : n.files) bundle.add(Document.typeOf(f));
            } else {
                List<DocType> inputs = new ArrayList<>();
                for (Connection c : model.incoming(n.id)) {
                    inputs.addAll(out.getOrDefault(c.fromNodeId(), List.of()));
                }
                if (n.kind.isReduce()) {
                    bundle.add(DocType.PDF);                 // collapses to one PDF
                } else {                                     // map: one PDF per input
                    for (int i = 0; i < inputs.size(); i++) bundle.add(DocType.PDF);
                }
            }
            out.put(n.id, bundle);
        }
        return out;
    }

    /** Output document count for a node, or 0 if it cannot be determined (e.g. cycle). */
    public static int outputCount(PipelineModel model, String nodeId) {
        try {
            return outputTypes(model).getOrDefault(nodeId, List.of()).size();
        } catch (CycleException e) {
            return 0;
        }
    }
}
