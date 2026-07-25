package com.pdfconduit.web.support;

import com.pdfconduit.core.service.BatchFailure;
import com.pdfconduit.core.service.NamedBytes;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P6: download filenames must be UTF-8 (RFC 5987) encoded so non-ASCII names survive the header. */
class ResponsesTest {

    @Test
    void contentDisposition_encodesNonAsciiNameAsRfc5987() {
        String header = Responses.contentDisposition("zażółć.pdf");

        // RFC 5987 extended field carries the real UTF-8 name; %-encoded, no raw non-ASCII bytes.
        assertTrue(header.contains("filename*=UTF-8''"), header);
        assertTrue(header.contains("attachment"), header);
        for (int i = 0; i < header.length(); i++) {
            assertTrue(header.charAt(i) < 128, "non-ASCII char leaked into header: " + header);
        }
    }

    @Test
    void file_streamsUtf8FilenameHeader() {
        ResponseEntity<byte[]> response = Responses.file(
            new NamedBytes("zażółć.pdf", new byte[]{1, 2, 3}), MediaType.APPLICATION_PDF);

        String header = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertNotNull(header);
        assertTrue(header.contains("filename*=UTF-8''"), header);
    }

    // --- X-Batch-Failures: "<file>: <reason>" entries joined by "; " -------

    @Test
    void batchFailures_joinsFileAndReason() {
        String header = Responses.batchFailures(List.of(
            new BatchFailure("a.pdf", "The PDF is password-protected."),
            new BatchFailure("b.pdf", "Could not read the PDF.")));

        assertEquals("a.pdf: The PDF is password-protected.; b.pdf: Could not read the PDF.", header);
    }

    @Test
    void batchFailures_capsTheListSoAHugeBatchCannotBlowTheHeader() {
        List<BatchFailure> many = new ArrayList<>();
        for (int i = 1; i <= 8; i++) many.add(new BatchFailure("f" + i + ".pdf", "broken"));

        String header = Responses.batchFailures(many);

        assertTrue(header.startsWith("f1.pdf: broken; "), header);
        assertTrue(header.endsWith("; +3 more"), header);
        assertFalse(header.contains("f6.pdf"), header);
    }

    @Test
    void batchFailures_isSafeToPutInAHeader() {
        String header = Responses.batchFailures(List.of(
            new BatchFailure("evil\r\nX-Injected: 1.pdf", "bad; message"),
            new BatchFailure("zażółć.pdf", "damaged")));

        // No response splitting, no stray separator, nothing outside the Latin-1 wire charset.
        assertFalse(header.contains("\r"), header);
        assertFalse(header.contains("\n"), header);
        assertTrue(header.startsWith("evil  X-Injected: 1.pdf: bad  message; "), header);
        for (int i = 0; i < header.length(); i++) {
            assertTrue(header.charAt(i) <= 0xff, "non Latin-1 char leaked into header: " + header);
        }
    }
}
