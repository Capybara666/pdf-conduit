package com.pdfconduit.core.service;

import java.nio.file.Path;

/** The per-input work of an operation: run it on a ready PDF, writing to {@code output}. */
@FunctionalInterface
public interface Execution<R> {
    R run(Path pdfInput, Path output) throws Exception;
}
