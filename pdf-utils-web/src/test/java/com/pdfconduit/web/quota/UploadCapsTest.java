package com.pdfconduit.web.quota;

import com.pdfconduit.web.config.WebProperties;
import com.pdfconduit.web.plan.FreePlanLimits;
import com.pdfconduit.web.plan.PlanLimits;
import com.pdfconduit.web.plan.PlanLimitsResolver;
import com.pdfconduit.web.principal.IpPrincipal;
import com.pdfconduit.web.principal.PrincipalResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.unit.DataSize;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link UploadCaps} is the one place the per-file byte cap and per-request file count are decided,
 * for BOTH the enforcing interceptor and the {@code /api/capabilities} advertisement. What it must
 * get right is that the advertised number is the <em>effective</em> one: a request has to survive
 * several independent gates, so the smallest of them is the only honest answer.
 */
class UploadCapsTest {

    private static final long MB = 1024L * 1024;

    /** All-defaults WebProperties with the two knobs this class reads made explicit. */
    private static WebProperties props(boolean quotaEnabled, int maxFilesPerRequest) {
        return new WebProperties(null, maxFilesPerRequest, null, null, null, null,
            new WebProperties.Quota(quotaEnabled, null, null, null),
            null, null, null, null, null, null);
    }

    private static MultipartProperties multipart(DataSize maxFileSize) {
        MultipartProperties mp = new MultipartProperties();
        mp.setMaxFileSize(maxFileSize);
        return mp;
    }

    private static PlanLimits plan(long maxFileSizeBytes, int maxFiles) {
        return new FreePlanLimits(60, maxFiles, maxFileSizeBytes, 3000, 300, 60_000_000L, 40, 10, 15);
    }

    private static final PrincipalResolver IP = request -> new IpPrincipal("1.2.3.4");

    private static UploadCaps caps(WebProperties props, MultipartProperties mp, PlanLimitsResolver plans) {
        return new UploadCaps(IP, plans, props, mp);
    }

    @Test
    void planCeilings_winWhenTheyAreTheStrictest() {
        UploadCaps caps = caps(props(true, 50), multipart(DataSize.ofMegabytes(100)),
            p -> plan(25 * MB, 15));

        UploadCaps.Caps effective = caps.forRequest(new MockHttpServletRequest());
        assertEquals(25 * MB, effective.maxFileSizeBytes(), "free-tier per-file cap is the strictest");
        assertEquals(15, effective.maxFilesPerRequest(), "free-tier file count is the strictest");
    }

    /**
     * The relaxed dev/local presets do exactly this: a 1 GB plan cap behind an unchanged 100 MB
     * multipart ceiling, and a plan file count at the absolute guardrail. Advertising the plan value
     * alone would promise 1 GB uploads that Tomcat kills before the interceptor ever runs.
     */
    @Test
    void deploymentGuardrails_winWhenThePlanIsLooser() {
        UploadCaps caps = caps(props(true, 20), multipart(DataSize.ofMegabytes(100)),
            p -> plan(1024 * MB, 500));

        UploadCaps.Caps effective = caps.forRequest(new MockHttpServletRequest());
        assertEquals(100 * MB, effective.maxFileSizeBytes(),
            "the multipart ceiling is the real per-file cap when the plan is looser");
        assertEquals(20, effective.maxFilesPerRequest(),
            "the absolute max-files-per-request guardrail is the real count when the plan is looser");
    }

    /** With the free tier off only the deployment-wide guardrails remain — which is what runs. */
    @Test
    void quotaDisabled_advertisesOnlyTheDeploymentGuardrails() {
        UploadCaps caps = caps(props(false, 7), multipart(DataSize.ofMegabytes(100)),
            p -> plan(1 * MB, 1));

        UploadCaps.Caps effective = caps.forRequest(new MockHttpServletRequest());
        assertEquals(100 * MB, effective.maxFileSizeBytes(), "no free-tier byte cap applies");
        assertEquals(7, effective.maxFilesPerRequest(), "the hard file-count guardrail still applies");
    }

    /** Spring encodes "no multipart limit" as a negative DataSize; it must not clamp everything to 0. */
    @Test
    void unlimitedMultipart_leavesThePlanCapInCharge() {
        UploadCaps caps = caps(props(true, 50), multipart(DataSize.ofBytes(-1)),
            p -> plan(25 * MB, 15));

        assertEquals(25 * MB, caps.forRequest(new MockHttpServletRequest()).maxFileSizeBytes());
    }

    /**
     * Resolved per request, not snapshotted at startup: swapping what the resolver returns changes
     * the very next answer. This is what keeps the advertisement honest once a paid tier resolves a
     * different plan per principal.
     */
    @Test
    void resolvesPerRequest_soAPerPrincipalPlanIsAdvertisedCorrectly() {
        PlanLimits[] current = {plan(25 * MB, 15)};
        UploadCaps caps = caps(props(true, 50), multipart(DataSize.ofMegabytes(100)),
            p -> current[0]);

        assertEquals(15, caps.forRequest(new MockHttpServletRequest()).maxFilesPerRequest());
        current[0] = plan(40 * MB, 30);
        UploadCaps.Caps after = caps.forRequest(new MockHttpServletRequest());
        assertEquals(30, after.maxFilesPerRequest(), "a per-request resolve must see the new plan");
        assertEquals(40 * MB, after.maxFileSizeBytes());
    }

    /** {@code forPlan} (what the interceptor enforces) and {@code forRequest} (what we advertise)
     *  must be the same computation, or the advertisement drifts from enforcement. */
    @Test
    void forPlan_andForRequest_agree() {
        PlanLimits p = plan(25 * MB, 15);
        UploadCaps caps = caps(props(true, 50), multipart(DataSize.ofMegabytes(100)), principal -> p);

        assertEquals(caps.forPlan(p), caps.forRequest(new MockHttpServletRequest()));
    }
}
