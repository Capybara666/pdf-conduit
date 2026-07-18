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
 * The free-tier daily quota: with a limit of two operations per IP, the first two succeed (and are
 * counted) while the third is rejected with 429 {@code quota_exceeded}. Rate limiting is disabled
 * so only the quota gate is under test.
 */
@SpringBootTest(properties = {
    "pdfconduit.web.quota.enabled=true",
    "pdfconduit.web.quota.daily-operations=2",
    "pdfconduit.web.ratelimit.enabled=false"
})
@AutoConfigureMockMvc
class QuotaTest {

    @Autowired
    private MockMvc mvc;

    private static MockMultipartFile pdf(byte[] bytes) {
        return new MockMultipartFile("files", "a.pdf", "application/pdf", bytes);
    }

    @Test
    void exceedingDailyQuota_returns429() throws Exception {
        byte[] a = TestPdfs.blank(1);

        // Only successful (2xx) operations are counted.
        mvc.perform(multipart("/api/rotate").file(pdf(a)).param("angle", "90"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Quota-Limit", "2"))
            .andExpect(header().string("X-Quota-Remaining", "2"));
        mvc.perform(multipart("/api/rotate").file(pdf(a)).param("angle", "90"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Quota-Remaining", "1"));

        mvc.perform(multipart("/api/rotate").file(pdf(a)).param("angle", "90"))
            .andExpect(status().is(429))
            .andExpect(jsonPath("$.code").value("quota_exceeded"))
            .andExpect(header().string("X-Quota-Remaining", "0"))
            .andExpect(header().exists("X-Quota-Reset"));
    }
}
