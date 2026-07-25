package com.pdfconduit.web;

import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.service.NamedBytes;
import com.pdfconduit.web.config.WebProperties;
import com.pdfconduit.web.guard.OfficeGuard;
import com.pdfconduit.web.observability.WebMetrics;
import com.pdfconduit.web.plan.FreePlanLimits;
import com.pdfconduit.web.plan.FreePlanLimitsResolver;
import com.pdfconduit.web.plan.PlanLimits;
import com.pdfconduit.web.plan.PlanLimitsResolver;
import com.pdfconduit.web.plan.RequestPlan;
import com.pdfconduit.web.principal.RequestPrincipal;
import com.pdfconduit.web.quota.QuotaService;
import com.pdfconduit.web.quota.QuotaStore;
import com.pdfconduit.web.service.WebOperations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves the three paid-tier seams (Principal / PlanLimits / QuotaStore) are real and, by default,
 * behaviour-preserving. It does two things a black-box HTTP test cannot: it confirms the sole
 * {@link FreePlanLimitsResolver} reproduces the {@link WebProperties} values verbatim (so no ceiling
 * moved), and it swaps a stub {@link PlanLimits} / {@link QuotaStore} into the guards to show
 * enforcement follows the seam, not hard-coded {@code free*} fields.
 */
class PlanSeamTest {

    /** All-defaults WebProperties (each null normalised to the documented default in the record). */
    private static WebProperties defaults() {
        return new WebProperties(
            null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void freeResolver_reproducesWebPropertiesVerbatim() {
        WebProperties props = defaults();
        PlanLimits plan = new FreePlanLimitsResolver(props).resolve(null);

        assertEquals(props.quota().dailyOperations(), plan.dailyOperations());
        assertEquals(props.quota().freeMaxFiles(), plan.maxFiles());
        assertEquals(props.quota().freeMaxFileSize().toBytes(), plan.maxFileSizeBytes());
        assertEquals(props.pdf().maxPages(), plan.maxPages());
        assertEquals(props.render().maxDpi(), plan.maxDpi());
        assertEquals(props.render().maxOutputPixels(), plan.maxOutputPixels());
        assertEquals(props.render().maxTotalOutputPixels(), plan.maxTotalOutputPixels());
        assertEquals(props.processing().maxTotalOutputBytes().toBytes(), plan.maxTotalOutputBytes());
        assertEquals(props.ratelimit().requestsPerMinute(), plan.rateRequestsPerMinute());
        assertEquals(props.ratelimit().heavyPerMinute(), plan.rateHeavyPerMinute());
        assertEquals(props.ratelimit().burst(), plan.rateBurst());
    }

    @Test
    void resolveDefault_matchesResolveForAnyPrincipal() {
        PlanLimitsResolver resolver = new FreePlanLimitsResolver(defaults());
        // One constant plan today: the no-principal default and a per-principal resolve are identical.
        assertEquals(resolver.resolveDefault().maxPages(),
            resolver.resolve((RequestPrincipal) () -> "user-42").maxPages());
    }

    @Test
    void quotaService_countsThroughInjectedStore() {
        RecordingStore store = new RecordingStore();
        QuotaService quota = new QuotaService(store);

        assertEquals(0, quota.used("k"));
        quota.increment("k");
        quota.increment("k");
        assertEquals(2, quota.used("k"), "QuotaService must count through the injected QuotaStore");
        assertEquals(2, store.counts.get("k").get());
        assertEquals(7L, quota.resetEpochSeconds(), "reset boundary is delegated to the store");
        // The daily limit is now a plan value the caller supplies, not baked into the service.
        assertEquals(1, quota.remaining("k", 3));
        assertEquals(3, quota.remaining("k", 5));
    }

    @Test
    void webOperations_enforcesResolvedPlanMaxPages() throws Exception {
        WebProperties props = defaults();
        WebMetrics metrics = new WebMetrics(new SimpleMeterRegistry());
        OfficeGuard officeGuard = new OfficeGuard(props, metrics);
        com.pdfconduit.web.guard.OcrGuard ocrGuard = new com.pdfconduit.web.guard.OcrGuard(props, metrics);
        NamedBytes twoPage = new NamedBytes("a.pdf", TestPdfs.blank(2));

        // Default FREE plan (maxPages 3000): a 2-page PDF passes the page-count guard.
        WebOperations lenient = new WebOperations(officeGuard, ocrGuard,
            outputBudget(props, new FreePlanLimitsResolver(props)),
            new com.pdfconduit.web.guard.DocumentLimits(requestPlan(new FreePlanLimitsResolver(props))),
            props);
        assertDoesNotThrow(() -> lenient.readMetadata(twoPage));

        // Swap in a stub plan with maxPages=1: the SAME operation is now rejected — proving the guard
        // reads its ceiling from the resolved PlanLimits seam, not from a hard-wired WebProperties field.
        PlanLimitsResolver stub = principal -> new FreePlanLimits(60, 15, 26_214_400L,
            /* maxPages */ 1, 300, 60_000_000L, 500_000_000L, 67_108_864L, 40, 10, 15);
        WebOperations strict = new WebOperations(officeGuard, ocrGuard, outputBudget(props, stub),
            new com.pdfconduit.web.guard.DocumentLimits(requestPlan(stub)), props);
        PdfOperationException ex =
            assertThrows(PdfOperationException.class, () -> strict.readMetadata(twoPage));
        assertEquals(true, ex.getMessage().contains("maximum page count"));
    }

    /**
     * A {@link RequestPlan} over {@code resolver} with no servlet request in scope, so
     * {@code current()} falls through to {@link PlanLimitsResolver#resolveDefault()} — the plain
     * unit-test path. Over HTTP the very same object resolves the caller's plan per request.
     */
    private static RequestPlan requestPlan(PlanLimitsResolver resolver) {
        return new RequestPlan(request -> new com.pdfconduit.web.principal.IpPrincipal("test"), resolver);
    }

    /** An {@link com.pdfconduit.web.guard.OutputBudget} over the same plan seam the guards read. */
    private static com.pdfconduit.web.guard.OutputBudget outputBudget(WebProperties props,
                                                                      PlanLimitsResolver resolver) {
        RequestPlan plan = requestPlan(resolver);
        return new com.pdfconduit.web.guard.OutputBudget(plan,
            new com.pdfconduit.web.cost.CostModel(props, plan));
    }

    /** Minimal in-test {@link QuotaStore} used to prove QuotaService counts through the seam. */
    private static final class RecordingStore implements QuotaStore {
        final ConcurrentHashMap<String, AtomicLong> counts = new ConcurrentHashMap<>();

        @Override public long used(String key) {
            return counts.getOrDefault(key, new AtomicLong()).get();
        }

        @Override public void increment(String key) {
            counts.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
        }

        @Override public long resetEpochSeconds() {
            return 7L;
        }
    }
}
