package com.pdfconduit.web;

import com.pdfconduit.web.plan.FreePlanLimits;
import com.pdfconduit.web.plan.PlanLimits;
import com.pdfconduit.web.plan.PlanLimitsResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The page-count and render ceilings must come from the plan resolved <b>per request</b>, not from a
 * snapshot taken in a singleton's constructor.
 *
 * <p>This test swaps in a resolver whose plan CHANGES BETWEEN REQUESTS, against a live context, and
 * shows the very same request is accepted or rejected accordingly — which a constructor snapshot
 * cannot do. It also covers the thread hop that makes this non-trivial: the operations run on
 * {@code LoadGuard}'s bounded executor, not on the request thread, so the resolved plan has to be
 * carried across.
 */
@SpringBootTest(properties = {
    "pdfconduit.web.ratelimit.enabled=false",
    "pdfconduit.web.quota.enabled=false"
})
@AutoConfigureMockMvc
@Import(PerRequestPlanTest.SwitchablePlanConfig.class)
class PerRequestPlanTest {

    /** The plan the resolver hands out right now; the tests move it between requests. */
    private static volatile PlanLimits current = plan(3000, 300);

    private static PlanLimits plan(int maxPages, int maxDpi) {
        return new FreePlanLimits(60, 15, 26_214_400L, maxPages, maxDpi, 60_000_000L,
            500_000_000L, 67_108_864L, 40, 10, 15);
    }

    @TestConfiguration
    static class SwitchablePlanConfig {
        /** Stands in for a future per-principal (paid) resolver: a different plan per request. */
        @Bean
        @Primary
        PlanLimitsResolver switchablePlanLimits() {
            return principal -> current;
        }
    }

    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void resetPlan() {
        current = plan(3000, 300);
    }

    private static MockMultipartFile pdf(String field, byte[] bytes) {
        return new MockMultipartFile(field, "a.pdf", "application/pdf", bytes);
    }

    /**
     * The DPI ceiling: the same 200 DPI render is refused under a 150 DPI plan and accepted under a
     * 600 DPI one, with nothing restarted in between.
     */
    @Test
    void renderDpiCeiling_followsThePlanResolvedForEachRequest() throws Exception {
        byte[] onePage = TestPdfs.blank(1);

        current = plan(3000, 150);
        mvc.perform(multipart("/api/to-images").file(pdf("files", onePage)).param("dpi", "200"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("bad_request"));

        current = plan(3000, 600);
        mvc.perform(multipart("/api/to-images").file(pdf("files", onePage)).param("dpi", "200"))
            .andExpect(status().isOk());
    }

    /**
     * The page ceiling, enforced deep inside an operation that runs on the load guard's worker
     * thread — the hop a naive thread-local plan would lose.
     */
    @Test
    void pageCountCeiling_followsThePlanResolvedForEachRequest() throws Exception {
        byte[] twoPages = TestPdfs.blank(2);

        current = plan(1, 300);
        mvc.perform(multipart("/api/rotate").file(pdf("files", twoPages)).param("angle", "90"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("operation_failed"))
            .andExpect(jsonPath("$.error",
                org.hamcrest.Matchers.containsString("maximum page count (1)")));

        current = plan(3000, 300);
        mvc.perform(multipart("/api/rotate").file(pdf("files", twoPages)).param("angle", "90"))
            .andExpect(status().isOk());
    }

    /** And the advertisement moves with it — capabilities is resolved from the same plan. */
    @Test
    void capabilitiesAdvertisesTheCurrentlyResolvedPlan() throws Exception {
        current = plan(3000, 150);
        mvc.perform(get("/api/capabilities"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.maxDpi").value(150));

        current = plan(3000, 600);
        mvc.perform(get("/api/capabilities"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.maxDpi").value(600));
    }
}
