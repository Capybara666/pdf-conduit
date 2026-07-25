package com.pdfconduit.web.plan;

/**
 * The single {@link PlanLimits} implementation today: the anonymous FREE tier, an immutable carrier
 * whose values are copied verbatim from {@link com.pdfconduit.web.config.WebProperties} by {@link
 * FreePlanLimitsResolver}. A future paid tier is just another {@code PlanLimits} value.
 */
public record FreePlanLimits(int dailyOperations, int maxFiles, long maxFileSizeBytes,
                             int maxPages, int maxDpi, long maxOutputPixels,
                             long maxTotalOutputPixels, long maxTotalOutputBytes,
                             int rateRequestsPerMinute, int rateHeavyPerMinute, int rateBurst)
        implements PlanLimits {
}
