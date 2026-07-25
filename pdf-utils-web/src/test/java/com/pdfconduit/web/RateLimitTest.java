package com.pdfconduit.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

        // Capacity is 3: the first three requests pass and carry the rate headers. X-RateLimit-Remaining
        // is POST-consume (tokens left after the current request), so it counts down 2, 1, 0.
        for (int i = 0; i < 3; i++) {
            mvc.perform(multipart("/api/rotate").file(pdf(a)).param("angle", "90"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "3"))
                .andExpect(header().string("X-RateLimit-Remaining", Integer.toString(2 - i)));
        }

        // The fourth is over the burst (refill is only 1/min) → rejected.
        mvc.perform(multipart("/api/rotate").file(pdf(a)).param("angle", "90"))
            .andExpect(status().is(429))
            .andExpect(jsonPath("$.code").value("rate_limited"))
            .andExpect(jsonPath("$.retryAfterSeconds").isNumber())
            .andExpect(header().exists("Retry-After"));
    }

    /**
     * {@code /api/form-fields} is read-only and quota-free, but it fully parses an arbitrary upload
     * and routes office documents through LibreOffice — so it MUST consume general-bucket tokens.
     * While it sat on the unmetered cheap allow-list, this burst was accepted indefinitely at zero
     * cost, saturating the load-guard and both soffice permits.
     */
    @Test
    void formFields_exceedingBurst_returns429() throws Exception {
        byte[] a = TestPdfs.blank(1);

        for (int i = 0; i < 3; i++) {
            mvc.perform(multipart("/api/form-fields").file(single(a)).with(from(CLIENT_B)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Remaining", Integer.toString(2 - i)));
        }

        mvc.perform(multipart("/api/form-fields").file(single(a)).with(from(CLIENT_B)))
            .andExpect(status().is(429))
            .andExpect(jsonPath("$.code").value("rate_limited"))
            .andExpect(header().exists("Retry-After"));
    }

    /**
     * {@code /api/metadata/read} is metered on the same terms — it is the other read-only analysis
     * that opens the uploaded document.
     */
    @Test
    void metadataRead_exceedingBurst_returns429() throws Exception {
        byte[] a = TestPdfs.blank(1);

        for (int i = 0; i < 3; i++) {
            mvc.perform(multipart("/api/metadata/read").file(single(a)).with(from(CLIENT_C)))
                .andExpect(status().isOk());
        }

        mvc.perform(multipart("/api/metadata/read").file(single(a)).with(from(CLIENT_C)))
            .andExpect(status().is(429))
            .andExpect(jsonPath("$.code").value("rate_limited"));
    }

    /**
     * The genuinely free endpoints stay exempt: health checks must never be throttled, however many
     * of them an uptime monitor fires.
     */
    @Test
    void freeEndpoints_areNeverRateLimited() throws Exception {
        for (int i = 0; i < 20; i++) {
            mvc.perform(get("/api/health").with(from(CLIENT_D)))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-RateLimit-Limit"));
        }
        for (int i = 0; i < 20; i++) {
            mvc.perform(get("/api/operations").with(from(CLIENT_D)))
                .andExpect(status().isOk());
        }
    }

    // Buckets are per client IP and the filter bean is shared across this class's methods, so each
    // test drives a distinct peer address rather than fighting over 127.0.0.1's three tokens.
    private static final String CLIENT_B = "198.51.100.11";
    private static final String CLIENT_C = "198.51.100.12";
    private static final String CLIENT_D = "198.51.100.13";

    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    /** Single-file endpoints take the part under the name {@code file}. */
    private static MockMultipartFile single(byte[] bytes) {
        return new MockMultipartFile("file", "a.pdf", "application/pdf", bytes);
    }
}
