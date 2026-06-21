package com.pdfconduit.core.service;

/** Receives batch progress (transport-agnostic; a JavaFX Task or a logger can adapt it). */
@FunctionalInterface
public interface ProgressSink {
    void report(int completed, int total);
    ProgressSink NONE = (completed, total) -> {};
}
