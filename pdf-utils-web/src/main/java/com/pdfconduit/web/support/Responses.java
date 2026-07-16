package com.pdfconduit.web.support;

import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.service.OperationRunner.BatchOutcome;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/** Builds attachment download responses and turns a batch outcome into a file-or-ZIP response. */
public final class Responses {

    public static final MediaType ZIP = MediaType.parseMediaType("application/zip");

    private Responses() {}

    /** An {@code attachment} response with the given bytes, filename and content type. */
    public static ResponseEntity<byte[]> file(byte[] bytes, String filename, MediaType type) {
        return ResponseEntity.ok()
            .contentType(type)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(filename).build().toString())
            .contentLength(bytes.length)
            .body(bytes);
    }

    /**
     * Finalises a single-input MAP batch: one clean output streams as {@code singleType};
     * many outputs (or any failures) become {@code <op>_results.zip}. Zero outputs means the
     * whole batch failed → a 422 carrying the first failure message. Reads all bytes into
     * memory, so it is safe to call before the workspace is closed.
     */
    public static ResponseEntity<byte[]> batch(String op, BatchOutcome outcome, MediaType singleType)
            throws PdfOperationException {
        List<Path> outputs = outcome.outputs();
        if (outputs.isEmpty()) {
            String reason = outcome.failures().isEmpty()
                ? "No output was produced."
                : outcome.failures().get(0).message();
            throw new PdfOperationException(reason);
        }
        if (outputs.size() == 1 && !outcome.hasFailures()) {
            Path only = outputs.get(0);
            return file(TempWorkspace.readAll(only), only.getFileName().toString(), singleType);
        }
        byte[] zip = Zips.zip(outputs, failuresText(outcome));
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
            .contentType(ZIP)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(op + "_results.zip").build().toString())
            .contentLength(zip.length);
        if (outcome.hasFailures()) {
            builder.header("X-Batch-Failures", String.valueOf(outcome.failures().size()));
        }
        return builder.body(zip);
    }

    /** Zips a fixed list of result files (used by extract-separate / to-images). */
    public static ResponseEntity<byte[]> zipFiles(List<Path> files, String zipName) {
        byte[] zip = Zips.zip(files, null);
        return file(zip, zipName, ZIP);
    }

    private static String failuresText(BatchOutcome outcome) {
        if (!outcome.hasFailures()) return null;
        return outcome.failures().stream()
            .map(f -> f.input() + ": " + f.message())
            .collect(Collectors.joining(System.lineSeparator()));
    }
}
