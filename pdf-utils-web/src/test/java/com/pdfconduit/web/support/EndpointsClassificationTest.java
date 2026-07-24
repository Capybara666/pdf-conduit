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
 * neither HEAVY nor on the explicit cheap allow-list.
 */
@SpringBootTest
class EndpointsClassificationTest {

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    /** A POST {@code /api/**} mapping is "classified" iff Endpoints treats it as heavy or cheap. */
    private static boolean isClassified(String path) {
        return Endpoints.isHeavy(path) || Endpoints.isCheap(path);
    }

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
            if (!isClassified(path)) unclassified.add(path);
        }
        assertTrue(unclassified.isEmpty(),
            "Unclassified POST /api endpoint(s) — each would run with no concurrency cap, no "
                + "timeout and no quota. Add to Endpoints.HEAVY (and, if it is an operation, "
                + "QUOTA_OPS), or to the cheap allow-list: " + unclassified);
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

    @Test
    void intendedCheapEndpoints_areNotHeavyOrQuota() {
        for (String cheap : new String[]{
            "/api/health", "/api/operations", "/api/pipeline/kinds",
            "/api/pipeline/validate", "/api/metadata/read"}) {
            assertTrue(Endpoints.isCheap(cheap), cheap + " should be on the cheap allow-list");
            assertFalse(Endpoints.isHeavy(cheap), cheap + " should NOT be HEAVY");
            assertFalse(Endpoints.isQuotaOp(cheap), cheap + " should NOT count against quota");
        }
    }
}
