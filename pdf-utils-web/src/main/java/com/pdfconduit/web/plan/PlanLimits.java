package com.pdfconduit.web.plan;

/**
 * The per-request entitlement set — everything the hardening guards previously read as the fixed
 * {@code free*} / limit values on {@link com.pdfconduit.web.config.WebProperties}. Today there is a
 * single {@link FreePlanLimits} plan built from those properties, so behaviour is unchanged; the
 * seam is that the quota interceptor, rate-limit filter and {@code WebOperations} guards read their
 * ceilings from a resolved {@code PlanLimits} rather than from {@code WebProperties} directly. A
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

    /** Rate limit: general-bucket refill (requests per minute). */
    int rateRequestsPerMinute();

    /** Rate limit: heavy-bucket refill (heavy requests per minute). */
    int rateHeavyPerMinute();

    /** Rate limit: general-bucket capacity (burst). */
    int rateBurst();
}
