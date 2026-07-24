package com.pdfconduit.web.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every operation endpoint must be classified HEAVY (S5/P2) so it runs under the load-guard's
 * concurrency / in-flight-byte / processing-timeout wrapper and the heavy rate bucket — including
 * the ones that previously slipped through (extract, arrange, protect, unlock, metadata, to-text).
 * Only the cheap read-only / catalog endpoints stay excluded.
 */
class EndpointsHeavyTest {

    @Test
    void allOperationEndpoints_areHeavy() {
        for (String op : new String[]{
            "/api/merge", "/api/extract", "/api/compress", "/api/rotate", "/api/arrange",
            "/api/to-pdf", "/api/to-images", "/api/to-text", "/api/protect", "/api/unlock",
            "/api/metadata", "/api/watermark", "/api/redact", "/api/ocr", "/api/render",
            "/api/pipeline/run"}) {
            assertTrue(Endpoints.isHeavy(op), op + " should be HEAVY");
        }
    }

    @Test
    void toText_isHeavy() {
        assertTrue(Endpoints.isHeavy("/api/to-text"));
    }

    @Test
    void cheapReadOnlyEndpoints_areNotHeavy() {
        for (String cheap : new String[]{
            "/api/health", "/api/operations", "/api/capabilities", "/api/pipeline/kinds",
            "/api/pipeline/validate", "/api/metadata/read"}) {
            assertFalse(Endpoints.isHeavy(cheap), cheap + " should not be HEAVY");
        }
    }
}
