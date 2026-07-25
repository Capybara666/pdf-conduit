package com.pdfconduit.core.service;

import com.pdfconduit.core.convert.DocumentConverter;
import com.pdfconduit.core.exception.BatchFatal;
import com.pdfconduit.core.exception.InvalidPageRangeException;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.exception.PdfUnrecoverableException;
import com.pdfconduit.core.model.PageSize;
import com.pdfconduit.core.util.Filenames;
import com.pdfconduit.core.util.PdfLoader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.util.ArrayList;
import java.util.List;

/**
 * The bytes analog of {@link OperationRunner}: reusable, disk-free plumbing for the
 * stateless web backend. It routes a raw upload to PDF bytes (PDF passthrough, image →
 * in-memory Image-to-PDF, office → the single temp-dir exception in
 * {@link DocumentConverter#ensurePdfBytes}), runs an operation, and — for batches — maps a
 * {@code List<byte[]>} of inputs to a {@code List<byte[]>} (or {@link NamedBytes}) of outputs.
 *
 * <p>Operation identity (id, suffix) is read from {@link OperationType}, so output names stay
 * consistent with every other surface. Nothing here touches disk except the documented office
 * conversion delegated to {@link DocumentConverter}.
 */
public final class MemoryOperations {

    private MemoryOperations() {}

    /** The per-input work: PDF bytes in, PDF bytes out. */
    @FunctionalInterface
    public interface BytesExecution {
        byte[] run(byte[] pdf) throws Exception;
    }

    /** The per-input work for multi-output ops (Extract-separate, to-images): PDF bytes in, many out. */
    @FunctionalInterface
    public interface MultiBytesExecution {
        List<byte[]> run(byte[] pdf) throws Exception;
    }

    /** The work for a REDUCE op (e.g. Merge): a bundle of PDF bytes in, one PDF's bytes out. */
    @FunctionalInterface
    public interface ReduceExecution {
        byte[] run(List<byte[]> pdfs) throws Exception;
    }

    /**
     * Routes {@code rawInput} (identified by {@code filename}) to PDF bytes, then runs {@code exec}.
     * PDFs pass through, images convert in memory, office docs go through the temp-dir exception.
     */
    public static byte[] runSingle(byte[] rawInput, String filename, BytesExecution exec)
            throws PdfOperationException {
        byte[] pdf = toPdfBytes(rawInput, filename);
        try {
            return exec.run(pdf);
        } catch (PdfOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(messageOf(e), e);
        }
    }

    /**
     * Runs {@code exec} once per input (MAP), returning one output per input as {@link NamedBytes}
     * named {@code <stem><suffix>.pdf} from {@code type}. A failing input aborts the batch — but
     * with the offending file <em>named</em> ({@link #named}), so the caller never has to bisect an
     * upload to find out which of fifteen files was the bad one.
     *
     * <p>For a batch that should survive a bad input, see {@link #mapPartial}.
     */
    public static List<NamedBytes> runBatch(OperationType type, List<byte[]> rawInputs,
                                            List<String> filenames, BytesExecution exec)
            throws PdfOperationException {
        List<NamedBytes> outputs = new ArrayList<>(rawInputs.size());
        for (int i = 0; i < rawInputs.size(); i++) {
            String filename = filenames.get(i);
            byte[] out;
            try {
                out = runSingle(rawInputs.get(i), filename, exec);
            } catch (PdfOperationException e) {
                throw named(filename, e);
            }
            outputs.add(new NamedBytes(outputName(type, filename), out));
        }
        return outputs;
    }

    /** The per-file work of a partial-tolerant batch: one input in, that input's outputs out. */
    @FunctionalInterface
    public interface FileWork {
        List<NamedBytes> run(NamedBytes input) throws PdfOperationException, InvalidPageRangeException;
    }

    /**
     * A <b>partial-tolerant</b> MAP batch: runs {@code work} per input and keeps going when one
     * fails, returning every output that was produced plus a {@link BatchFailure} per bad input
     * (both in input order). One password-protected file in a fifteen-file compress therefore costs
     * the user that one file — not the whole upload.
     *
     * <p>Deliberate limits:
     * <ul>
     *   <li>if <em>every</em> input fails there is nothing to return, so the first failure is
     *       thrown (named) — an empty archive would be a worse answer than an error;</li>
     *   <li>only a <em>per-file</em> {@link PdfOperationException} is tolerated. An
     *       {@link InvalidPageRangeException} (the caller's own parameter is wrong, not the file),
     *       anything marked {@link BatchFatal} (a per-request ceiling: the batch as a whole is the
     *       problem, so a partial ZIP would be the wrong answer) and any runtime failure propagate
     *       and fail the whole request, as before;</li>
     *   <li>REDUCE operations (Merge) must never come through here — a merge missing an input is a
     *       different document, not a partial success.</li>
     * </ul>
     */
    public static BatchOutcome mapPartial(List<NamedBytes> inputs, FileWork work)
            throws PdfOperationException, InvalidPageRangeException {
        List<NamedBytes> outputs = new ArrayList<>(inputs.size());
        List<BatchFailure> failures = new ArrayList<>();
        PdfOperationException first = null;
        for (NamedBytes in : inputs) {
            try {
                outputs.addAll(work.run(in));
            } catch (PdfOperationException e) {
                // A per-request ceiling is not this file's fault and must not be softened into one
                // entry of X-Batch-Failures: fail the request, as the whole-batch guard intends.
                if (e instanceof BatchFatal) throw e;
                if (first == null) first = named(in.filename(), e);
                failures.add(new BatchFailure(in.filename(), messageOf(e)));
            }
        }
        if (outputs.isEmpty() && first != null) throw first;
        return new BatchOutcome(List.copyOf(outputs), List.copyOf(failures));
    }

    /**
     * {@code e} with {@code filename} prefixed to its message — the in-memory loaders have no file
     * name to work with ("The PDF is password-protected."), so the batch layer adds it back.
     *
     * <p>Type and cause are preserved: a {@link PdfUnrecoverableException} stays unrecoverable (the
     * web layer maps it to its own {@code repair_failed} code) and the original throwable is kept as
     * the cause. An exception whose message already mentions the file, or an unknown subclass whose
     * type could not be reproduced faithfully, is returned untouched.
     */
    public static PdfOperationException named(String filename, PdfOperationException e) {
        String message = messageOf(e);
        if (filename == null || filename.isBlank() || message.contains(filename)) return e;
        String withName = filename + ": " + message;
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        if (e.getClass() == PdfUnrecoverableException.class) {
            return new PdfUnrecoverableException(withName, cause);
        }
        if (e.getClass() == PdfOperationException.class) {
            return new PdfOperationException(withName, cause);
        }
        return e;
    }

    /**
     * Runs a multi-output op against a single input (its raw bytes routed to PDF first), naming the
     * results {@code <stem><suffix>.pdf} / {@code <stem><suffix>_N.pdf} from {@code type}.
     */
    public static List<NamedBytes> runMulti(OperationType type, byte[] rawInput, String filename,
                                            MultiBytesExecution exec) throws PdfOperationException {
        byte[] pdf = toPdfBytes(rawInput, filename);
        List<byte[]> parts;
        try {
            parts = exec.run(pdf);
        } catch (PdfOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(messageOf(e), e);
        }
        String stem = stemOf(filename);
        List<NamedBytes> outputs = new ArrayList<>(parts.size());
        int width = Integer.toString(Math.max(1, parts.size())).length();
        for (int i = 0; i < parts.size(); i++) {
            String name = parts.size() == 1
                ? stem + type.suffix() + ".pdf"
                : stem + type.suffix() + "_" + pad(i + 1, width) + ".pdf";
            outputs.add(new NamedBytes(name, parts.get(i)));
        }
        return outputs;
    }

    /**
     * Routes each raw input to PDF bytes and hands the list to {@code reduce} (a REDUCE op such as
     * Merge), returning its single output named from {@code type} and the first input's stem.
     *
     * <p>A REDUCE is never partial: a merge missing one of its inputs is a different document, so a
     * bad input still fails the whole operation — only now the message names the file.
     */
    public static NamedBytes runReduce(OperationType type, List<byte[]> rawInputs,
                                       List<String> filenames, ReduceExecution reduce)
            throws PdfOperationException {
        List<byte[]> pdfs = new ArrayList<>(rawInputs.size());
        for (int i = 0; i < rawInputs.size(); i++) {
            String filename = filenames.get(i);
            try {
                pdfs.add(toPdfBytes(rawInputs.get(i), filename));
            } catch (PdfOperationException e) {
                throw named(filename, e);
            }
        }
        byte[] out;
        try {
            out = reduce.run(pdfs);
        } catch (PdfOperationException e) {
            // The reduce works on a bundle, so it cannot say which input broke it — find out here.
            String culprit = culprit(pdfs, filenames);
            throw culprit != null ? named(culprit, e) : e;
        } catch (Exception e) {
            throw new PdfOperationException(messageOf(e), e);
        }
        String stem = filenames.isEmpty() ? type.id() : stemOf(filenames.get(0));
        return new NamedBytes(stem + type.suffix() + ".pdf", out);
    }

    /**
     * Diagnostic-only: the first input that cannot be opened on its own ({@code null} if they all
     * open), so a failed REDUCE can name the file that spoiled it. Deliberately runs <em>after</em>
     * the failure — the happy path never pays for this second parse.
     */
    private static String culprit(List<byte[]> pdfs, List<String> filenames) {
        for (int i = 0; i < Math.min(pdfs.size(), filenames.size()); i++) {
            try (PDDocument ignored = PdfLoader.load(pdfs.get(i))) {
                // opens cleanly — not this one
            } catch (Exception e) {
                return filenames.get(i);
            }
        }
        return null;
    }

    /** In-memory input routing: PDF passthrough, image → Image-to-PDF, office → temp-dir exception. */
    public static byte[] toPdfBytes(byte[] data, String filename) throws PdfOperationException {
        return DocumentConverter.ensurePdfBytes(data, filename, PageSize.FIT);
    }

    /** {@code <stem><suffix>.pdf} for {@code filename}. */
    public static String outputName(OperationType type, String filename) {
        return stemOf(filename) + type.suffix() + ".pdf";
    }

    private static String stemOf(String filename) {
        return Filenames.stem(filename);
    }

    private static String pad(int n, int width) {
        return String.format("%0" + Math.max(1, width) + "d", n);
    }

    private static String messageOf(Throwable t) {
        String m = t.getMessage();
        return (m != null && !m.isBlank()) ? m : t.getClass().getSimpleName();
    }
}
