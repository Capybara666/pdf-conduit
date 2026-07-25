package com.pdfconduit.web.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guard against a new operation endpoint silently running unguarded/unmetered.
 *
 * <p>{@link Endpoints} is the single source of truth that gates the load-guard, the heavy rate
 * bucket and the quota interceptor. If someone adds a new {@code @PostMapping} under {@code /api}
 * but forgets to add it to {@code HEAVY} (and, for op endpoints, {@code QUOTA_OPS}), the endpoint
 * would run with no concurrency cap, no processing timeout and no quota. This test enumerates the
 * live Spring handler mappings and fails when any POST {@code /api/**} path is unclassified —
 * in none of the three tiers (HEAVY / METERED_CHEAP / the free allow-list).
 */
@SpringBootTest
class EndpointsClassificationTest {

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    /** Collects every POST-accepting path under {@code /api}, from the actual handler mappings. */
    private Set<String> postApiPaths() {
        Set<String> paths = new TreeSet<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            // An empty methods condition means the handler accepts ALL verbs (including POST).
            boolean acceptsPost = methods.isEmpty() || methods.contains(RequestMethod.POST);
            if (!acceptsPost) continue;
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                if (Endpoints.isApi(pattern)) paths.add(pattern);
            }
        }
        return paths;
    }

    @Test
    void everyPostApiEndpoint_isClassifiedInEndpoints() {
        Set<String> posts = postApiPaths();
        // Sanity: we actually discovered the operation surface (not an empty/broken mapping scan).
        assertTrue(posts.contains("/api/merge"),
            "handler scan found no /api/merge — mapping enumeration is broken, got: " + posts);

        List<String> unclassified = new ArrayList<>();
        for (String path : posts) {
            if (!Endpoints.isClassified(path)) unclassified.add(path);
        }
        assertTrue(unclassified.isEmpty(),
            "Unclassified POST /api endpoint(s) — each would run with no concurrency cap, no "
                + "timeout and no quota. Add to Endpoints.HEAVY (and, if it is an operation, "
                + "QUOTA_OPS), to METERED_CHEAP (read-only but parses an upload), or to the free "
                + "allow-list: " + unclassified);
    }

    /** The three tiers must not overlap: a path may be free, metered-cheap or heavy — never two. */
    @Test
    void tiersAreMutuallyExclusive() {
        Set<String> posts = postApiPaths();
        for (String path : posts) {
            int tiers = (Endpoints.isCheap(path) ? 1 : 0)
                + (Endpoints.isMeteredCheap(path) ? 1 : 0)
                + (Endpoints.isHeavy(path) ? 1 : 0);
            assertTrue(tiers == 1, path + " must be in exactly one tier, found " + tiers);
        }
    }

    /** Only HEAVY endpoints may consume daily quota, and every quota op must be heavy. */
    @Test
    void quotaOps_areAlwaysHeavy() {
        for (String path : postApiPaths()) {
            if (Endpoints.isQuotaOp(path)) {
                assertTrue(Endpoints.isHeavy(path),
                    path + " counts quota, so it must also run under the heavy guard");
            }
        }
    }

    @Test
    void pageMarks_isHeavyAndQuotaCounted_notCheap() {
        // /api/page-marks is a full mutating PDF op (runs under LoadGuard); it must be rate-limited
        // and counted against the free-tier daily quota, never on the cheap allow-list.
        assertTrue(Endpoints.isHeavy("/api/page-marks"), "/api/page-marks should be HEAVY");
        assertTrue(Endpoints.isQuotaOp("/api/page-marks"),
            "/api/page-marks should count against quota");
        assertFalse(Endpoints.isCheap("/api/page-marks"),
            "/api/page-marks must NOT be on the cheap allow-list");
        assertTrue(Endpoints.isMetered("/api/page-marks"),
            "/api/page-marks should be metered by the general rate bucket");
    }

    /**
     * The genuinely free tier: catalog / no-upload endpoints. None of these opens a user file, so
     * they stay exempt from the general rate bucket as well as from quota and the load-guard.
     */
    @Test
    void freeEndpoints_areUnmeteredAndNotHeavyOrQuota() {
        for (String free : new String[]{
            "/api/health", "/api/operations", "/api/capabilities", "/api/pipeline/kinds",
            "/api/pipeline/validate"}) {
            assertTrue(Endpoints.isCheap(free), free + " should be on the free allow-list");
            assertFalse(Endpoints.isMetered(free), free + " should NOT be rate-limited");
            assertFalse(Endpoints.isHeavy(free), free + " should NOT be HEAVY");
            assertFalse(Endpoints.isQuotaOp(free), free + " should NOT count against quota");
        }
    }

    /**
     * {@code /metadata/read} and {@code /form-fields} are read-only, so they must stay FREE OF
     * QUOTA — inspecting a file should never burn one of the day's operations. But they are not
     * free of cost: both fully parse an arbitrary upload and route office documents through
     * LibreOffice, so they MUST be metered by the general rate bucket. Leaving them on the
     * unmetered allow-list let a caller pin the load-guard and both soffice permits for free.
     */
    @Test
    void readOnlyAnalyses_areRateLimitedButQuotaFree() {
        for (String path : new String[]{"/api/metadata/read", "/api/form-fields"}) {
            assertTrue(Endpoints.isMeteredCheap(path), path + " should be METERED_CHEAP");
            assertTrue(Endpoints.isMetered(path),
                path + " parses an arbitrary upload — it MUST consume a general rate-bucket token");
            assertFalse(Endpoints.isCheap(path),
                path + " must NOT be on the unmetered free allow-list");
            assertFalse(Endpoints.isQuotaOp(path),
                path + " is read-only — it must NOT consume a daily quota unit");
            assertFalse(Endpoints.isHeavy(path), path + " should not be on the heavy bucket");
        }
    }

    /**
     * {@code /api/render} rasterises a page under the load-guard but deliberately costs no quota
     * (the SPA previews pages while the user works). It must still be metered.
     */
    @Test
    void render_isHeavyAndMetered_butQuotaFree() {
        assertTrue(Endpoints.isHeavy("/api/render"), "/api/render should be HEAVY");
        assertTrue(Endpoints.isMetered("/api/render"), "/api/render should be rate-limited");
        assertFalse(Endpoints.isQuotaOp("/api/render"),
            "/api/render is a preview — it must NOT consume a daily quota unit");
    }
}
