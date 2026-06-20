package org.example.core.pipeline;

/**
 * A single validation problem. {@code nodeId} is the offending node (may be null
 * for graph-wide issues such as a cycle).
 */
public record ValidationError(String nodeId, String message) {}
