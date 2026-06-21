package com.pdfconduit.core.service;

import com.pdfconduit.core.convert.DocumentConverter;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.PageSize;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reusable plumbing shared by every surface (CLI, GUI, pipeline, future web):
 * convert a raw input to PDF, run the operation, name the output from
 * {@link OperationType#suffix()}, and clean up temporary files. JavaFX-free.
 */
public final class OperationRunner {

    private OperationRunner() {}

    /** {@code <stem><suffix>.pdf} for {@code input}. */
    public static String outputName(OperationType type, Path input) {
        String name = input.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot >= 0 ? name.substring(0, dot) : name;
        return stem + type.suffix() + ".pdf";
    }

    /**
     * Converts {@code rawInput} to a PDF if needed, runs {@code exec} against it
     * writing to {@code out}, then deletes any temp created by conversion.
     * Exceptions propagate unchanged (callers add context where appropriate).
     */
    public static <R> R run(Path rawInput, Path out, Execution<R> exec) throws Exception {
        List<Path> temps = new ArrayList<>();
        try {
            Path pdf = DocumentConverter.ensurePdf(rawInput, PageSize.FIT, temps);
            return exec.run(pdf, out);
        } finally {
            for (Path t : temps) {
                try { Files.deleteIfExists(t); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Runs {@code exec} once per input, writing {@code <stem><suffix>.pdf} into
     * {@code outputDir}. Reports progress after each file. Per-file failures are
     * rethrown as {@code <filename>: <message>}.
     */
    public static List<Path> runBatch(OperationType type, List<Path> rawInputs, Path outputDir,
                                      Execution<?> exec, ProgressSink progress)
            throws PdfOperationException {
        return runBatch(type, rawInputs, outputDir, exec, progress, () -> false);
    }

    /**
     * As {@link #runBatch(OperationType, List, Path, Execution, ProgressSink)} but
     * stops cleanly once {@code cancelled} reports true. Cancellation is checked
     * between files, so the in-progress file is always finished whole (no partial
     * output) and already-written files remain. Returns whatever was produced so far.
     */
    public static List<Path> runBatch(OperationType type, List<Path> rawInputs, Path outputDir,
                                      Execution<?> exec, ProgressSink progress,
                                      java.util.function.BooleanSupplier cancelled)
            throws PdfOperationException {
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new PdfOperationException("Cannot create output folder: " + e.getMessage(), e);
        }
        ProgressSink sink = progress == null ? ProgressSink.NONE : progress;
        List<Path> outputs = new ArrayList<>();
        for (int i = 0; i < rawInputs.size(); i++) {
            if (cancelled.getAsBoolean()) break;
            Path in = rawInputs.get(i);
            Path out = outputDir.resolve(outputName(type, in));
            try {
                run(in, out, exec);
            } catch (Exception e) {
                throw new PdfOperationException(in.getFileName() + ": " + e.getMessage(), e);
            }
            outputs.add(out);
            sink.report(i + 1, rawInputs.size());
        }
        return outputs;
    }
}
