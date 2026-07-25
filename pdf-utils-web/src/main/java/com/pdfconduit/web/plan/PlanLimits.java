package com.pdfconduit.web.plan;

/**
 * The per-request entitlement set — every ceiling the hardening guards enforce. Today there is a
 * single {@link FreePlanLimits} plan, built from the {@code free*} / limit values on
 * {@link com.pdfconduit.web.config.WebProperties}; the seam is that the quota interceptor,
 * rate-limit filter and {@code WebOperations} guards read their ceilings from a resolved
 * {@code PlanLimits} rather than from {@code WebProperties} directly. A
 * later paid tier is a different {@code PlanLimits} resolved per {@link
 * com.pdfconduit.web.principal.RequestPrincipal} — no guard logic changes.
 */
public interface PlanLimits {

    /** Successful operations allowed per calendar day (quota gate). */
    int dailyOperations();

    /** Free-tier per-request file-count ceiling (stricter than the absolute multipart cap). */
    int maxFiles();

    /** Free-tier per-uploaded-file size ceiling, in bytes. */
    long maxFileSizeBytes();

    /** PDF-bomb guard: maximum page count of any processed PDF. */
    int maxPages();

    /** Raster-render guard: maximum requested DPI. */
    int maxDpi();

    /** Raster-render guard: maximum rendered pixel area for any single page. */
    long maxOutputPixels();

    /**
     * Aggregate render ceiling: the summed pixel area of every page ONE request may rasterise,
     * across all its files. The per-page {@link #maxOutputPixels()} does not bound pages × files.
     */
    long maxTotalOutputPixels();

    /**
     * Aggregate result ceiling: the result bytes ONE request may accumulate, across all its files
     * and pages. The <em>granted</em> budget is this value narrowed by the server's memory pool —
     * see {@link com.pdfconduit.web.cost.CostModel#perRequestOutputBytes(PlanLimits)} — so a plan
     * can never entitle a caller to more than the heap affords.
     */
    long maxTotalOutputBytes();

    /** Rate limit: general-bucket refill (requests per minute). */
    int rateRequestsPerMinute();

    /** Rate limit: heavy-bucket refill (heavy requests per minute). */
    int rateHeavyPerMinute();

    /** Rate limit: general-bucket capacity (burst). */
    int rateBurst();
}
