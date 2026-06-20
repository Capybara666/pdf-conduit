package org.example.core.pipeline;

/**
 * A directed edge from one node's output to another node's input. Input ordering
 * for multi-input nodes is the order connections appear in
 * {@link PipelineModel#connections}.
 */
public record Connection(String fromNodeId, String toNodeId) {}
