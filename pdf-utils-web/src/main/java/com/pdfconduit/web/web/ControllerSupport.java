package com.pdfconduit.web.web;

import com.pdfconduit.core.exception.InvalidPageRangeException;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.service.BatchOutcome;
import com.pdfconduit.core.service.MemoryOperations;
import com.pdfconduit.core.service.NamedBytes;
import com.pdfconduit.web.guard.OutputBudget;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** Shared request-validation helpers for the REST controllers. */
final class ControllerSupport {

    private ControllerSupport() {}

    /**
     * A partial-tolerant MAP batch run under the request's aggregate output budget.
     *
     * <p>Every one-output-per-file operation (rotate, compress, extract-combine, watermark, …)
     * accumulates one full result per input in the heap and then copies the lot again into a ZIP,
     * exactly like the multi-output paths — and nothing else bounds that sum: the multipart limit
     * and the free-tier caps only bound the INPUT, so a 200 MB upload would otherwise be echoed
     * back through an in-memory archive. Every file's results commit into ONE request-wide
     * {@code tally} while the batch runs, so an over-budget request is refused (422
     * {@code output_too_large}) instead of OOM-ing at the end.
     *
     * <p><b>Partial tolerance is preserved exactly.</b> The commit happens only after a file's work
     * succeeds, so a per-file defect still surfaces as that file's {@code X-Batch-Failures} entry.
     * A blown budget is the opposite kind of failure — it describes the REQUEST, not the file that
     * happened to tip it over — so {@link com.pdfconduit.web.error.OutputTooLargeException} is
     * {@link com.pdfconduit.core.exception.BatchFatal} and {@link MemoryOperations#mapPartial}
     * rethrows it, failing the whole request rather than returning a partial ZIP that blames an
     * innocent file.
     */
    static BatchOutcome mapBounded(OutputBudget.Tally tally, List<NamedBytes> inputs,
                                   MemoryOperations.FileWork work)
            throws PdfOperationException, InvalidPageRangeException {
        return MemoryOperations.mapPartial(inputs, in -> {
            List<NamedBytes> outputs = work.run(in);
            tally.commitResults(outputs);
            return outputs;
        });
    }

    /** Summed byte size of already-read uploads (the load-guard's in-flight estimate). */
    static long totalBytes(List<NamedBytes> inputs) {
        long sum = 0;
        for (NamedBytes in : inputs) sum += in.data().length;
        return sum;
    }

    /** Rejects an empty upload or one exceeding the per-request file guardrail (→ 400). */
    static void guardCount(List<MultipartFile> files, int max) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("No files were uploaded.");
        }
        if (files.size() > max) {
            throw new IllegalArgumentException(
                "Too many files: " + files.size() + " (limit " + max + " per request).");
        }
    }

    /** Ensures a chosen output name ends in {@code .pdf}. */
    static String ensurePdf(String name) {
        String n = name.strip();
        return n.toLowerCase().endsWith(".pdf") ? n : n + ".pdf";
    }
}
