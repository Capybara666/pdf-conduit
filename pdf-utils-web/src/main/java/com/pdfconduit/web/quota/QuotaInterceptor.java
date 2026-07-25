package com.pdfconduit.web.quota;

import com.pdfconduit.web.config.WebProperties;
import com.pdfconduit.web.error.TooLargeException;
import com.pdfconduit.web.observability.WebMetrics;
import com.pdfconduit.web.plan.PlanLimits;
import com.pdfconduit.web.plan.PlanLimitsResolver;
import com.pdfconduit.web.principal.PrincipalResolver;
import com.pdfconduit.web.principal.RequestPrincipal;
import com.pdfconduit.web.support.Endpoints;
import com.pdfconduit.web.support.JsonErrors;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.Map;

/**
 * The free-tier gate for {@code /api/**}. Runs after the rate-limit filter and before the
 * controller. It:
 * <ul>
 *   <li>enforces the absolute per-request file-count cap on <em>every</em> uploading endpoint
 *       (including the single-file ones and {@code /pipeline/run}, which the controllers' own
 *       guardCount skips) → 400;</li>
 *   <li>enforces the free-tier per-file size cap and per-request file cap on <em>every</em>
 *       uploading endpoint → 413 {@code too_large};</li>
 *   <li>rejects requests once the caller's daily free operation count is spent → 429
 *       {@code quota_exceeded}, and emits {@code X-Quota-*} headers;</li>
 *   <li>counts a successful operation (2xx) in {@code afterCompletion} so failures don't burn quota.</li>
 * </ul>
 *
 * <p><strong>Scope split.</strong> The size/count caps apply to any {@code /api/**} multipart POST,
 * not just the quota-counting ones: {@code /api/render}, {@code /api/metadata/read} and
 * {@code /api/form-fields} all parse an arbitrary uploaded document, so letting them accept the raw
 * 100 MB multipart ceiling while every real operation is capped at 25 MB was a free way to pin the
 * load-guard. Only the <em>daily count</em> (check + increment) stays behind
 * {@link Endpoints#isQuotaOp(String)}, so inspecting a file still costs no operations.
 *
 * <p>The free-tier checks and counting are skipped entirely when {@code quota.enabled=false}; the
 * hard file-count cap always applies.
 *
 * <p><strong>Advertised = enforced.</strong> The per-file byte cap and per-request file count come
 * from {@link UploadCaps}, the same component {@code GET /api/capabilities} advertises them from, so
 * the SPA's client-side pre-upload guard is the server's own number rather than a hard-coded copy.
 */
@Component
public class QuotaInterceptor implements HandlerInterceptor {

    private final QuotaService quota;
    private final PrincipalResolver principals;
    private final PlanLimitsResolver planLimits;
    private final UploadCaps uploadCaps;
    private final WebMetrics metrics;
    private final boolean quotaEnabled;

    public QuotaInterceptor(QuotaService quota, PrincipalResolver principals, PlanLimitsResolver planLimits,
                            UploadCaps uploadCaps, WebMetrics metrics, WebProperties props) {
        this.quota = quota;
        this.principals = principals;
        this.planLimits = planLimits;
        // The per-file/per-request ceilings enforced below are the SAME object GET /api/capabilities
        // advertises, so the client-side guard cannot drift from the server-side one.
        this.uploadCaps = uploadCaps;
        this.metrics = metrics;
        // System-level toggle stays sourced from WebProperties; the free-tier ceilings (per-file
        // count/size, daily operations) come from the resolved PlanLimits via UploadCaps.
        this.quotaEnabled = props.quota().enabled();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String path = Endpoints.path(request);
        if (!Endpoints.isApi(path)) return true;
        boolean quotaOp = Endpoints.isQuotaOp(path);

        // Every uploading /api endpoint is size-capped, not only the quota-counting ones.
        List<MultipartFile> files = uploadedFiles(request);

        // Absolute file-count guard, applied uniformly (single-file endpoints + pipeline/run too).
        int hardMaxFiles = uploadCaps.hardMaxFiles();
        if (files.size() > hardMaxFiles) {
            throw new IllegalArgumentException(
                "Too many files: " + files.size() + " (limit " + hardMaxFiles + " per request).");
        }

        if (!quotaEnabled) return true;
        // Nothing left to do for a non-quota endpoint that carries no upload (GET catalogs, the
        // JSON-only /pipeline/validate): skip the principal/plan resolution entirely.
        if (files.isEmpty() && !quotaOp) return true;

        // The caller's entitlements for this request (today: the constant FREE plan).
        RequestPrincipal principal = principals.resolve(request);
        PlanLimits plan = planLimits.resolve(principal);
        int dailyLimit = plan.dailyOperations();
        // The EFFECTIVE ceilings (plan value narrowed by the deployment-wide guardrails) — exactly
        // the pair GET /api/capabilities hands the client, resolved from the same plan.
        UploadCaps.Caps caps = uploadCaps.forPlan(plan);
        int maxFiles = caps.maxFilesPerRequest();
        long maxFileBytes = caps.maxFileSizeBytes();

        // Free-tier per-request and per-file caps (stricter than the absolute multipart ceiling).
        // Applied to EVERY uploading endpoint — /api/render and the read-only analyses included.
        if (files.size() > maxFiles) {
            throw new TooLargeException("Free tier allows at most " + maxFiles
                + " files per request; received " + files.size() + ".");
        }
        for (MultipartFile f : files) {
            if (f.getSize() > maxFileBytes) {
                throw new TooLargeException("File \"" + safeName(f)
                    + "\" exceeds the free-tier per-file limit of "
                    + DataSize.ofBytes(maxFileBytes).toMegabytes() + " MB.");
            }
        }

        // Beyond this point: the daily free operation count, which only real operations consume.
        if (!quotaOp) return true;

        // Daily free quota.
        String key = principal.id();
        long used = quota.used(key);
        boolean exhausted = used >= dailyLimit;
        // The header must reflect POST-request state: a request that proceeds here will be counted
        // on success (afterCompletion), so advertise the remaining allowance AFTER this op counts.
        // For an exhausted (blocked) request this clamps to 0. The actual count is still incremented
        // only in afterCompletion on a 2xx, so there is no double-counting.
        long remainingAfter = Math.max(0, dailyLimit - (used + 1));
        response.setHeader("X-Quota-Limit", Integer.toString(dailyLimit));
        response.setHeader("X-Quota-Remaining", Long.toString(exhausted ? 0 : remainingAfter));
        response.setHeader("X-Quota-Reset", Long.toString(quota.resetEpochSeconds()));
        if (exhausted) {
            metrics.quotaExhausted();
            JsonErrors.write(response, 429, "quota_exceeded",
                "Daily free limit reached. Please try again after the quota resets.",
                Map.of("resetEpochSeconds", quota.resetEpochSeconds()));
            return false;
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (!quotaEnabled) return;
        if (!Endpoints.isQuotaOp(Endpoints.path(request))) return;
        int status = response.getStatus();
        if (status >= 200 && status < 300) {
            quota.increment(principals.resolve(request).id());
        }
    }

    private static List<MultipartFile> uploadedFiles(HttpServletRequest request) {
        if (!(request instanceof MultipartHttpServletRequest mp)) return List.of();
        return mp.getMultiFileMap().values().stream().flatMap(List::stream).toList();
    }

    private static String safeName(MultipartFile f) {
        String n = f.getOriginalFilename();
        return (n == null || n.isBlank()) ? "upload" : n;
    }
}
