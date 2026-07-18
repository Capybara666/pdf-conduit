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
 * The free-tier daily quota: with a limit of three operations per IP, the first three succeed (and
 * are counted) while the fourth is rejected with 429 {@code quota_exceeded}. Rate limiting is
 * disabled so only the quota gate is under test.
 *
 * <p>The {@code X-Quota-Remaining} header reflects POST-request state: on a successful counted op it
 * shows the allowance remaining AFTER this op counts (2, then 1, then 0), never the pre-count value.
 */
@SpringBootTest(properties = {
    "pdfconduit.web.quota.enabled=true",
    "pdfconduit.web.quota.daily-operations=3",
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

        // Only successful (2xx) operations are counted, and X-Quota-Remaining is the POST-count value.
        mvc.perform(multipart("/api/rotate").file(pdf(a)).param("angle", "90"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Quota-Limit", "3"))
            .andExpect(header().string("X-Quota-Remaining", "2"));
        mvc.perform(multipart("/api/rotate").file(pdf(a)).param("angle", "90"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Quota-Remaining", "1"));
        mvc.perform(multipart("/api/rotate").file(pdf(a)).param("angle", "90"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Quota-Remaining", "0"));

        // The fourth op is over quota → rejected, still reporting 0 remaining.
        mvc.perform(multipart("/api/rotate").file(pdf(a)).param("angle", "90"))
            .andExpect(status().is(429))
            .andExpect(jsonPath("$.code").value("quota_exceeded"))
            .andExpect(header().string("X-Quota-Remaining", "0"))
            .andExpect(header().exists("X-Quota-Reset"));
    }
}
