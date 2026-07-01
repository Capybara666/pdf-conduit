package com.pdfconduit.core.service;

import com.pdfconduit.core.convert.DocumentConverter;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.PageSize;

import com.pdfconduit.core.util.OutputPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Reusable plumbing shared by every surface (CLI, GUI, pipeline, future web):
 * convert a raw input to PDF, run the operation, name the output from
 * {@link OperationType#suffix()}, and clean up temporary files. JavaFX-free.
 */
public final class OperationRunner {

    private OperationRunner() {}

    /**
     * What a batch run should do when an output path already exists. Batch runs can
     * process dozens of files, so — unlike a single-file run — they can't stop to ask
     * per file; the surface picks one policy up front. A result that would land on one
     * of the batch's own <em>input</em> files is always renamed regardless of policy
     * (overwriting a source is data loss, never what the user meant).
     */
    public enum OverwritePolicy { OVERWRITE, RENAME }

    /**
     * The result of a batch run: the outputs that were produced, the inputs that were
     * skipped (with the reason), how many outputs were renamed to avoid clobbering, and
     * how many inputs were attempted (i.e. not skipped by cancellation).
     */
    public record BatchOutcome(List<Path> outputs, List<Failure> failures, int renamed, int attempted) {

        /** A single input that could not be processed, and why. */
        public record Failure(String input, String message) {}

        /** Inputs that completed successfully. */
        public int done() { return attempted - failures.size(); }

        /** True when at least one input was skipped. */
        public boolean hasFailures() { return !failures.isEmpty(); }

        /** True when nothing needed renaming and nothing failed — the quiet, happy path. */
        public boolean clean() { return failures.isEmpty() && renamed == 0; }
    }

    /** The per-input work for {@link #runBatchMulti}: one PDF in, any number of files out. */
    @FunctionalInterface
    public interface MultiExecution {
        /** Runs against {@code pdf} (the input already converted to PDF); {@code rawInput} is the original, for naming. */
        void run(Path pdf, Path rawInput) throws Exception;
    }

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
     * {@code outputDir} under {@code policy}. Reports progress after each file.
     * Per-file failures are collected, not thrown, so one bad file never aborts the
     * batch (see {@link BatchOutcome}).
     */
    public static BatchOutcome runBatch(OperationType type, List<Path> rawInputs, Path outputDir,
                                        Execution<?> exec, ProgressSink progress, OverwritePolicy policy)
            throws PdfOperationException {
        return runBatch(type, rawInputs, outputDir, exec, progress, policy, () -> false);
    }

    /**
     * As {@link #runBatch(OperationType, List, Path, Execution, ProgressSink, OverwritePolicy)}
     * but stops cleanly once {@code cancelled} reports true. Cancellation is checked
     * between files, so the in-progress file is always finished whole (no partial
     * output) and already-written files remain.
     *
     * <p>Data-safety guarantees for every input:
     * <ul>
     *   <li>a result that would land on any of the batch's own inputs is renamed (never
     *       overwrites a source);
     *   <li>an existing (non-input) collision is renamed under {@link OverwritePolicy#RENAME}
     *       or overwritten under {@link OverwritePolicy#OVERWRITE};
     *   <li>a file that fails is recorded and the batch continues.
     * </ul>
     */
    public static BatchOutcome runBatch(OperationType type, List<Path> rawInputs, Path outputDir,
                                        Execution<?> exec, ProgressSink progress,
                                        OverwritePolicy policy, BooleanSupplier cancelled)
            throws PdfOperationException {
        createDir(outputDir);
        ProgressSink sink = progress == null ? ProgressSink.NONE : progress;
        List<Path> outputs = new ArrayList<>();
        List<BatchOutcome.Failure> failures = new ArrayList<>();
        int renamed = 0, attempted = 0;
        for (int i = 0; i < rawInputs.size(); i++) {
            if (cancelled.getAsBoolean()) break;
            Path in = rawInputs.get(i);
            Path desired = outputDir.resolve(outputName(type, in));
            Path out = safeOutput(desired, rawInputs, policy);
            if (!out.equals(desired)) renamed++;
            attempted++;
            try {
                run(in, out, exec);
                outputs.add(out);
            } catch (Exception e) {
                failures.add(new BatchOutcome.Failure(in.getFileName().toString(), messageOf(e)));
            }
            sink.report(i + 1, rawInputs.size());
        }
        return new BatchOutcome(outputs, failures, renamed, attempted);
    }

    /**
     * Batch runner for operations that emit <em>many</em> files per input (Split-separate,
     * PDF→images, PDF→text): each input is converted to a PDF, handed to {@code exec} — which
     * writes its own outputs into {@code outputDir} — then the converted temp is cleaned up.
     * Like {@link #runBatch}, a failing input is recorded (not thrown) and cancellation is
     * checked between files. The returned {@link BatchOutcome#outputs()} is empty (these ops
     * name their own files); {@code failures}/{@code attempted} carry the useful signal.
     */
    public static BatchOutcome runBatchMulti(List<Path> rawInputs, Path outputDir,
                                             MultiExecution exec, ProgressSink progress,
                                             BooleanSupplier cancelled)
            throws PdfOperationException {
        createDir(outputDir);
        ProgressSink sink = progress == null ? ProgressSink.NONE : progress;
        List<BatchOutcome.Failure> failures = new ArrayList<>();
        int attempted = 0;
        for (int i = 0; i < rawInputs.size(); i++) {
            if (cancelled.getAsBoolean()) break;
            Path in = rawInputs.get(i);
            attempted++;
            List<Path> temps = new ArrayList<>();
            try {
                Path pdf = DocumentConverter.ensurePdf(in, PageSize.FIT, temps);
                exec.run(pdf, in);
            } catch (Exception e) {
                failures.add(new BatchOutcome.Failure(in.getFileName().toString(), messageOf(e)));
            } finally {
                for (Path t : temps) {
                    try { Files.deleteIfExists(t); } catch (IOException ignored) {}
                }
            }
            sink.report(i + 1, rawInputs.size());
        }
        return new BatchOutcome(List.of(), failures, 0, attempted);
    }

    /**
     * The path to actually write {@code desired} to: renamed off any of {@code inputs}
     * (never clobber a source — A2), then adjusted for an existing collision per
     * {@code policy} (A1). Exposed for batch loops that name their own outputs.
     */
    public static Path safeOutput(Path desired, List<Path> inputs, OverwritePolicy policy) {
        for (Path in : inputs) {
            if (isSameFile(desired, in)) return OutputPaths.uniquePath(desired);
        }
        if (policy == OverwritePolicy.RENAME) return OutputPaths.uniquePath(desired);
        return desired;
    }

    /** True when {@code a} and {@code b} name the same existing file. */
    private static boolean isSameFile(Path a, Path b) {
        try {
            if (Files.exists(a) && Files.exists(b)) return Files.isSameFile(a, b);
        } catch (IOException ignored) { /* fall through to path comparison */ }
        return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }

    private static void createDir(Path outputDir) throws PdfOperationException {
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new PdfOperationException("Cannot create output folder: " + e.getMessage(), e);
        }
    }

    /** A non-null, human-readable message for a thrown exception. */
    private static String messageOf(Throwable t) {
        String m = t.getMessage();
        return (m != null && !m.isBlank()) ? m : t.getClass().getSimpleName();
    }
}
