package com.pdfconduit.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The per-IP rate limiter, exercised with a tiny burst (capacity 3, ~1/min refill) so the fourth
 * request from the same IP is rejected with 429 {@code rate_limited} + {@code Retry-After}. Only
 * this test enables rate limiting (the shared test config disables it).
 */
@SpringBootTest(properties = {
    "pdfconduit.web.ratelimit.enabled=true",
    "pdfconduit.web.ratelimit.requests-per-minute=1",
    "pdfconduit.web.ratelimit.burst=3",
    "pdfconduit.web.ratelimit.heavy-per-minute=100",
    "pdfconduit.web.quota.enabled=false"
})
@AutoConfigureMockMvc
class RateLimitTest {

    @Autowired
    private MockMvc mvc;

    private static MockMultipartFile pdf(byte[] bytes) {
        return new MockMultipartFile("files", "a.pdf", "application/pdf", bytes);
    }

    @Test
    void exceedingBurst_returns429() throws Exception {
        byte[] a = TestPdfs.blank(1);

        // Capacity is 3: the first three requests pass and carry the rate headers.
        for (int i = 0; i < 3; i++) {
            mvc.perform(multipart("/api/rotate").file(pdf(a)).param("angle", "90"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-RateLimit-Limit"))
                .andExpect(header().exists("X-RateLimit-Remaining"));
        }

        // The fourth is over the burst (refill is only 1/min) → rejected.
        mvc.perform(multipart("/api/rotate").file(pdf(a)).param("angle", "90"))
            .andExpect(status().is(429))
            .andExpect(jsonPath("$.code").value("rate_limited"))
            .andExpect(jsonPath("$.retryAfterSeconds").isNumber())
            .andExpect(header().exists("Retry-After"));
    }
}
