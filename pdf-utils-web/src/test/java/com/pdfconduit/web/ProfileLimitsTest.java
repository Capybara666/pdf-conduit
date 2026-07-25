package com.pdfconduit.web;

import com.pdfconduit.web.config.WebProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves per-environment limit profiles actually apply. With the {@code local} profile active,
 * the RELAXED presets in {@code application-local.yml} must override the strict public defaults
 * baked into {@code application.yml} / {@link WebProperties}. If Spring were not loading the
 * profile-specific file (wrong file name, wrong activation, key typo), the bound values would
 * fall back to the strict base default and these assertions would fail.
 *
 * <p>It also proves the profile changes what {@code GET /api/capabilities} ADVERTISES, not only
 * what the guards enforce — the SPA sizes its pre-upload guard from that response, so an
 * advertisement that ignored the active profile would refuse work this deployment would do.
 */
@SpringBootTest(properties = {
    // src/test/resources/application.yml SHADOWS the main application.yml (same classpath name),
    // so the multipart ceiling would otherwise sit at Spring's 1 MB default instead of the 100 MB
    // this service actually deploys with. Restate it here so the advertised per-file cap is the one
    // a real local deployment reports. (Profile files, application-local.yml, are not shadowed.)
    "spring.servlet.multipart.max-file-size=100MB"
})
@ActiveProfiles("local")
@AutoConfigureMockMvc
class ProfileLimitsTest {

    /** The strict public base default from application.yml (25 MB) — what we must beat. */
    private static final long STRICT_FREE_MAX_FILE_SIZE_BYTES = 25L * 1024 * 1024;

    /** The strict public base default for the free-tier per-request file count. */
    private static final int STRICT_FREE_MAX_FILES = 15;

    /** The multipart per-file ceiling (application.yml, not relaxed by any profile). */
    private static final long MULTIPART_MAX_FILE_BYTES = 100L * 1024 * 1024;

    @Autowired
    private WebProperties props;

    @Autowired
    private MockMvc mvc;

    @Test
    void localProfile_relaxesFreeMaxFileSizeAboveStrictBaseDefault() {
        long relaxed = props.quota().freeMaxFileSize().toBytes();
        assertTrue(relaxed > STRICT_FREE_MAX_FILE_SIZE_BYTES,
            "local profile free-max-file-size (" + relaxed + " bytes) must exceed the strict "
                + "public default (" + STRICT_FREE_MAX_FILE_SIZE_BYTES + " bytes) — proving "
                + "application-local.yml overrides application.yml");
        assertEquals(1024L * 1024 * 1024, relaxed, "local profile pins free-max-file-size to 1GB");
    }

    @Test
    void localProfile_relaxesRateLimitAndConcurrencyAndPages() {
        // Each of these is strictly greater than its application.yml public default.
        assertTrue(props.ratelimit().requestsPerMinute() > 40, "requests-per-minute relaxed above base 40");
        assertTrue(props.ratelimit().heavyPerMinute() > 10, "heavy-per-minute relaxed above base 10");
        assertTrue(props.concurrency().maxHeavyOps() > 4, "max-heavy-ops relaxed above base 4");
        assertTrue(props.pdf().maxPages() > 3000, "pdf.max-pages relaxed above base 3000");
        assertTrue(props.render().maxDpi() > 300, "render.max-dpi relaxed above base 300");
        assertTrue(props.pipeline().maxNodes() > 50, "pipeline.max-nodes relaxed above base 50");
        assertTrue(props.pipeline().maxConnections() > 100,
            "pipeline.max-connections relaxed above base 100");
    }

    /**
     * The advertisement follows the active profile. Locally the plan's per-file cap (1 GB) is looser
     * than the unchanged 100 MB multipart ceiling, so the EFFECTIVE — and therefore advertised —
     * per-file cap is 100 MB: still far above the strict public 25 MB, and the number a caller
     * really gets, which is exactly why the SPA must not hard-code either one.
     */
    @Test
    void localProfile_changesWhatCapabilitiesAdvertises() throws Exception {
        mvc.perform(get("/api/capabilities"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.maxFileSizeBytes").value(MULTIPART_MAX_FILE_BYTES))
            .andExpect(jsonPath("$.maxFilesPerRequest").value(props.quota().freeMaxFiles()));

        assertTrue(MULTIPART_MAX_FILE_BYTES > STRICT_FREE_MAX_FILE_SIZE_BYTES,
            "advertised per-file cap must exceed the strict public default — otherwise the profile "
                + "relaxed enforcement but not the advertisement");
        assertTrue(props.quota().freeMaxFiles() > STRICT_FREE_MAX_FILES,
            "local profile relaxes free-max-files above the strict public " + STRICT_FREE_MAX_FILES);
    }

    /** The aggregate output budget is a per-env preset too — relaxed locally, strict in public. */
    @Test
    void localProfile_relaxesAggregateOutputBudget() {
        assertTrue(props.render().maxTotalOutputPixels() > 500_000_000L,
            "render.max-total-output-pixels relaxed above the strict base 500 MP");
        assertTrue(props.processing().maxTotalOutputBytes().toBytes() > 64L * 1024 * 1024,
            "processing.max-total-output-bytes relaxed above the strict base 64 MB");
    }
}
