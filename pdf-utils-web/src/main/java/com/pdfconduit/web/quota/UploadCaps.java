package com.pdfconduit.web.quota;

import com.pdfconduit.web.config.WebProperties;
import com.pdfconduit.web.plan.PlanLimits;
import com.pdfconduit.web.plan.PlanLimitsResolver;
import com.pdfconduit.web.principal.PrincipalResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * The single source of truth for the two upload ceilings a caller actually hits: the per-file byte
 * cap and the per-request file count. {@link QuotaInterceptor} ENFORCES these numbers and
 * {@code GET /api/capabilities} ADVERTISES them, both through this one component — so the frontend
 * can size its client-side guard from the server instead of hard-coding a copy that silently drifts
 * the moment a limit changes.
 *
 * <p><strong>Effective, not nominal.</strong> A request passes several independent gates, so the
 * number that matters is the smallest one:
 * <ul>
 *   <li><em>files</em> — {@code min(pdfconduit.web.max-files-per-request, plan.maxFiles())}. The
 *       absolute cap rejects with 400, the plan cap with 413; the largest count that survives both
 *       is the minimum. (When the plan cap is the looser of the two, the absolute cap fires first
 *       and the plan check is unreachable, so folding them into one minimum changes no outcome.)</li>
 *   <li><em>bytes</em> — {@code min(spring.servlet.multipart.max-file-size, plan.maxFileSizeBytes())}.
 *       Tomcat rejects an oversize part before the interceptor ever runs, so advertising the plan
 *       value alone would lie wherever the plan is the looser one — which is exactly the case in the
 *       relaxed dev/local presets (1 GB plan vs the 100 MB multipart ceiling).</li>
 * </ul>
 *
 * <p><strong>Resolved per request, never snapshotted.</strong> {@link #forRequest} resolves the
 * {@link com.pdfconduit.web.principal.RequestPrincipal} and its {@link PlanLimits} on every call, so
 * a future per-principal paid tier is advertised correctly to each caller without touching this
 * class. Only the deployment-wide ceilings ({@code max-files-per-request}, the multipart limit,
 * {@code quota.enabled}) are read once at startup — they are bound from configuration and cannot
 * change without a restart.
 */
@Component
public class UploadCaps {

    /**
     * The effective upload ceilings in force for one request.
     *
     * @param maxFileSizeBytes largest single uploaded file that will be accepted, in bytes
     * @param maxFilesPerRequest most files one request may carry
     */
    public record Caps(long maxFileSizeBytes, int maxFilesPerRequest) {}

    private final PrincipalResolver principals;
    private final PlanLimitsResolver planLimits;
    private final boolean quotaEnabled;
    private final int hardMaxFiles;
    private final long multipartMaxFileBytes;

    public UploadCaps(PrincipalResolver principals, PlanLimitsResolver planLimits,
                      WebProperties props, MultipartProperties multipart) {
        this.principals = principals;
        this.planLimits = planLimits;
        this.quotaEnabled = props.quota().enabled();
        this.hardMaxFiles = props.maxFilesPerRequest();
        this.multipartMaxFileBytes = bytes(multipart.getMaxFileSize());
    }

    /** The absolute per-request file-count guardrail, applied even when the free tier is off. */
    public int hardMaxFiles() {
        return hardMaxFiles;
    }

    /**
     * The ceilings this caller will be held to. With the free tier disabled only the deployment-wide
     * guardrails remain (absolute file count + the multipart byte ceiling).
     */
    public Caps forRequest(HttpServletRequest request) {
        if (!quotaEnabled) return new Caps(multipartMaxFileBytes, hardMaxFiles);
        return forPlan(planLimits.resolve(principals.resolve(request)));
    }

    /**
     * The ceilings for an already-resolved plan — used by {@link QuotaInterceptor}, which resolves
     * the principal and plan once and reuses them for the daily-quota gate.
     */
    public Caps forPlan(PlanLimits plan) {
        return new Caps(Math.min(multipartMaxFileBytes, plan.maxFileSizeBytes()),
            Math.min(hardMaxFiles, plan.maxFiles()));
    }

    /** Spring's "unlimited" for a multipart size is a negative {@link DataSize}. */
    private static long bytes(DataSize size) {
        if (size == null) return Long.MAX_VALUE;
        long value = size.toBytes();
        return value < 0 ? Long.MAX_VALUE : value;
    }
}
