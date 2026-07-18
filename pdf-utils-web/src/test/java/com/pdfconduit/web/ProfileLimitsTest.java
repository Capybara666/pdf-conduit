package com.pdfconduit.web;

import com.pdfconduit.web.config.WebProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves per-environment limit profiles actually apply. With the {@code local} profile active,
 * the RELAXED presets in {@code application-local.yml} must override the strict public defaults
 * baked into {@code application.yml} / {@link WebProperties}. If Spring were not loading the
 * profile-specific file (wrong file name, wrong activation, key typo), the bound values would
 * fall back to the strict base default and these assertions would fail.
 */
@SpringBootTest
@ActiveProfiles("local")
class ProfileLimitsTest {

    /** The strict public base default from application.yml (25 MB) — what we must beat. */
    private static final long STRICT_FREE_MAX_FILE_SIZE_BYTES = 25L * 1024 * 1024;

    @Autowired
    private WebProperties props;

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
    }
}
