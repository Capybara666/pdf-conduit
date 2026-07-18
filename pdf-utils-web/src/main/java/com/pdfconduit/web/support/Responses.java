package com.pdfconduit.web.support;

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
}
