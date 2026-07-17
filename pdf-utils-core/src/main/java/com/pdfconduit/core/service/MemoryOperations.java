package com.pdfconduit.core.service;

import com.pdfconduit.core.convert.DocumentConverter;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.model.PageSize;

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
     * named {@code <stem><suffix>.pdf} from {@code type}. A failing input aborts with its message
     * (the web layer maps a single request to a single response, so there is no partial batch here).
     */
    public static List<NamedBytes> runBatch(OperationType type, List<byte[]> rawInputs,
                                            List<String> filenames, BytesExecution exec)
            throws PdfOperationException {
        List<NamedBytes> outputs = new ArrayList<>(rawInputs.size());
        for (int i = 0; i < rawInputs.size(); i++) {
            String filename = filenames.get(i);
            byte[] out = runSingle(rawInputs.get(i), filename, exec);
            outputs.add(new NamedBytes(outputName(type, filename), out));
        }
        return outputs;
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
     */
    public static NamedBytes runReduce(OperationType type, List<byte[]> rawInputs,
                                       List<String> filenames, ReduceExecution reduce)
            throws PdfOperationException {
        List<byte[]> pdfs = new ArrayList<>(rawInputs.size());
        for (int i = 0; i < rawInputs.size(); i++) {
            pdfs.add(toPdfBytes(rawInputs.get(i), filenames.get(i)));
        }
        byte[] out;
        try {
            out = reduce.run(pdfs);
        } catch (PdfOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(messageOf(e), e);
        }
        String stem = filenames.isEmpty() ? type.id() : stemOf(filenames.get(0));
        return new NamedBytes(stem + type.suffix() + ".pdf", out);
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
        String name = (filename == null || filename.isBlank()) ? "file" : filename;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return stem.isBlank() ? "file" : stem;
    }

    private static String pad(int n, int width) {
        return String.format("%0" + Math.max(1, width) + "d", n);
    }

    private static String messageOf(Throwable t) {
        String m = t.getMessage();
        return (m != null && !m.isBlank()) ? m : t.getClass().getSimpleName();
    }
}
