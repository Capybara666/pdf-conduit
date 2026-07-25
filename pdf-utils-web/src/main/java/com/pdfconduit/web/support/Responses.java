package com.pdfconduit.web.support;

import com.pdfconduit.core.service.BatchFailure;
import com.pdfconduit.core.service.BatchOutcome;
import com.pdfconduit.core.service.NamedBytes;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Builds in-memory attachment download responses (a single file, or many zipped). */
public final class Responses {

    public static final MediaType ZIP = MediaType.parseMediaType("application/zip");

    /**
     * Header naming the inputs a partial-tolerant batch could not process. Exposed to the browser by
     * {@code CorsConfig} and rendered verbatim by the SPA (see {@code api.service.ts} →
     * {@code RunResult.batchFailures}); the format is {@code <file>: <reason>} entries joined by
     * {@code "; "}.
     */
    public static final String BATCH_FAILURES = "X-Batch-Failures";

    /** At most this many failures are listed; the rest are summarised (headers are size-capped). */
    private static final int MAX_LISTED_FAILURES = 5;

    private Responses() {}

    /**
     * Builds a UTF-8-safe {@code attachment} Content-Disposition. Non-ASCII names (Polish, CJK, …)
     * are RFC 5987 encoded as {@code filename*=UTF-8''…}; passing the charset makes Spring emit both
     * that and an ASCII-sanitised {@code filename=} fallback for legacy clients.
     */
    public static String contentDisposition(String filename) {
        return ContentDisposition.attachment()
            .filename(filename, StandardCharsets.UTF_8)
            .build()
            .toString();
    }

    /** An {@code attachment} response with the given bytes, filename and content type. */
    public static ResponseEntity<byte[]> file(byte[] bytes, String filename, MediaType type) {
        return ResponseEntity.ok()
            .contentType(type)
            .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(filename))
            .contentLength(bytes.length)
            .body(bytes);
    }

    /** An {@code attachment} response streaming a single {@link NamedBytes}. */
    public static ResponseEntity<byte[]> file(NamedBytes result, MediaType type) {
        return file(result.data(), result.filename(), type);
    }

    /** Zips {@code results} into an attachment named {@code zipName}. */
    public static ResponseEntity<byte[]> zip(List<NamedBytes> results, String zipName) {
        return file(Zips.zip(results), zipName, ZIP);
    }

    /**
     * A MAP batch: a single result streams as {@code singleType}; several results become
     * {@code <op>_results.zip}. Both are fully in-memory.
     */
    public static ResponseEntity<byte[]> batch(String op, List<NamedBytes> results, MediaType singleType) {
        if (results.size() == 1) {
            return file(results.get(0), singleType);
        }
        return zip(results, op + "_results.zip");
    }

    /**
     * A partial-tolerant MAP batch. With no failures this is exactly
     * {@link #batch(String, List, MediaType)}. With failures it always zips — even when a single
     * input survived — because the archive names what came back, and adds {@link #BATCH_FAILURES}
     * naming what did not.
     */
    public static ResponseEntity<byte[]> batch(String op, BatchOutcome outcome, MediaType singleType) {
        if (!outcome.partial()) {
            return batch(op, outcome.outputs(), singleType);
        }
        return zip(outcome, op + "_results.zip");
    }

    /** Zips a partial-tolerant batch's outputs, reporting its failures in {@link #BATCH_FAILURES}. */
    public static ResponseEntity<byte[]> zip(BatchOutcome outcome, String zipName) {
        byte[] archive = Zips.zip(outcome.outputs());
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
            .contentType(ZIP)
            .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(zipName))
            .contentLength(archive.length);
        if (outcome.partial()) {
            builder.header(BATCH_FAILURES, batchFailures(outcome.failures()));
        }
        return builder.body(archive);
    }

    /**
     * Formats batch failures for the header: {@code <file>: <reason>} entries joined by {@code "; "},
     * capped at {@link #MAX_LISTED_FAILURES} entries plus a {@code "; +N more"} tail so a large batch
     * cannot blow the header budget.
     */
    static String batchFailures(List<BatchFailure> failures) {
        StringBuilder sb = new StringBuilder();
        int listed = Math.min(failures.size(), MAX_LISTED_FAILURES);
        for (int i = 0; i < listed; i++) {
            if (i > 0) sb.append("; ");
            BatchFailure f = failures.get(i);
            sb.append(headerSafe(f.filename())).append(": ")
              .append(headerSafe(reason(f.filename(), f.message())));
        }
        if (failures.size() > listed) {
            sb.append("; +").append(failures.size() - listed).append(" more");
        }
        return sb.toString();
    }

    /**
     * The failure reason with the file name stripped off the front, because this formatter puts it
     * back. Some failures reach a batch already named — {@code MemoryOperations.named} prefixes the
     * file whenever an operation fails inside a multi-file run, since the in-memory loaders have no
     * name of their own — and joining that with the name again reads as
     * {@code "locked.pdf: locked.pdf: The PDF is password-protected."}.
     */
    private static String reason(String filename, String message) {
        if (filename == null || filename.isBlank() || message == null) return message;
        String prefix = filename + ": ";
        return message.startsWith(prefix) ? message.substring(prefix.length()) : message;
    }

    /**
     * Makes a user-supplied fragment safe to put in a header value: control characters (CR/LF —
     * response-splitting) and the {@code ;} separator become spaces, and anything outside the
     * Latin-1 header wire charset becomes {@code ?} rather than being silently mangled.
     */
    private static String headerSafe(String text) {
        if (text == null || text.isBlank()) return "";
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ';' || c < 0x20 || c == 0x7f) out.append(' ');
            else if (c > 0xff) out.append('?');
            else out.append(c);
        }
        return out.toString().strip();
    }
}
