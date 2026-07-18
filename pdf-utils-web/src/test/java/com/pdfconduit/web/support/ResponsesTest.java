package com.pdfconduit.web.support;

import com.pdfconduit.core.service.NamedBytes;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

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
}
