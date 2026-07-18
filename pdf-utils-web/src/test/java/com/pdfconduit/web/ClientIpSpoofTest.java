package com.pdfconduit.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the X-Forwarded-For trust fix (S4/P4): the real client IP is the rightmost token NOT from
 * a trusted proxy — the address the edge actually appended — not the forgeable leftmost token. So a
 * client that rotates the leftmost XFF hop each request does NOT mint a fresh rate-limit bucket and
 * cannot bypass the limiter; a genuinely different client still gets its own bucket.
 *
 * <p>Only 127.0.0.1 (the MockMvc socket peer) is trusted here, so the request's real client is the
 * last XFF token our "edge" appended.
 */
@SpringBootTest(properties = {
    "pdfconduit.web.ratelimit.enabled=true",
    "pdfconduit.web.ratelimit.requests-per-minute=1",
    "pdfconduit.web.ratelimit.burst=1",
    "pdfconduit.web.ratelimit.heavy-per-minute=100",
    "pdfconduit.web.quota.enabled=false",
    "pdfconduit.web.trusted-proxies=127.0.0.1/32"
})
@AutoConfigureMockMvc
class ClientIpSpoofTest {

    @Autowired
    private MockMvc mvc;

    private static MockMultipartFile pdf(byte[] bytes) {
        return new MockMultipartFile("files", "a.pdf", "application/pdf", bytes);
    }

    @Test
    void forgedLeftmostXff_doesNotResetBucket() throws Exception {
        byte[] a = TestPdfs.blank(1);

        // First request from real client 203.0.113.9 (edge-appended, rightmost) — consumes its 1 token.
        mvc.perform(multipart("/api/rotate").file(pdf(a)).param("angle", "90")
                .header("X-Forwarded-For", "1.1.1.1, 203.0.113.9"))
            .andExpect(status().isOk());

        // Same real client, but a DIFFERENT forged leftmost hop. Under the old leftmost-trust bug this
        // would be a brand-new key (200); with rightmost-untrusted it is the SAME bucket → 429.
        mvc.perform(multipart("/api/rotate").file(pdf(a)).param("angle", "90")
                .header("X-Forwarded-For", "2.2.2.2, 203.0.113.9"))
            .andExpect(status().is(429))
            .andExpect(jsonPath("$.code").value("rate_limited"));

        // A genuinely different real client still gets its own bucket (not collapsed onto one key).
        mvc.perform(multipart("/api/rotate").file(pdf(a)).param("angle", "90")
                .header("X-Forwarded-For", "9.9.9.9, 198.51.100.7"))
            .andExpect(status().isOk());
    }
}
