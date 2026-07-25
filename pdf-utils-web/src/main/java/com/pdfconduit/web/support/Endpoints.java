package com.pdfconduit.web.support;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Set;

/**
 * The single source of truth for classifying {@code /api/**} endpoints for the hardening layer.
 * Every POST under {@code /api} falls into exactly one of three tiers:
 *
 * <ul>
 *   <li>{@link #CHEAP} — pure catalog / no-upload endpoints: not metered by the general rate
 *       bucket, not counted against quota, not load-guarded. They do no work on user data.</li>
 *   <li>{@link #METERED_CHEAP} — light read-only analyses that nevertheless parse an arbitrary
 *       uploaded document (and may shell out to LibreOffice for an office upload): free of the
 *       daily quota, but metered by the general rate bucket so they cannot be hammered for free.</li>
 *   <li>{@link #HEAVY} — real operations: heavy rate bucket + load-guard (concurrency /
 *       in-flight-byte / processing-timeout). Most of them additionally count a daily quota unit
 *       ({@link #QUOTA_OPS}).</li>
 * </ul>
 *
 * <p>Keeping this in one place stops the rate-limit filter, the quota interceptor and the
 * controllers from drifting apart; {@code EndpointsClassificationTest} fails when a new POST
 * mapping lands in none of the three tiers.
 */
public final class Endpoints {

    private Endpoints() {}

    /**
     * Catalog / no-upload endpoints: free of the general rate bucket AND of the daily quota.
     * Nothing here parses an uploaded document, so an unmetered flood costs only the servlet
     * thread. {@code /api/pipeline/validate} qualifies: it takes a JSON string parameter and
     * validates the graph in memory — it never opens a PDF.
     */
    private static final Set<String> CHEAP = Set.of(
        "/api/health",
        "/api/operations",
        "/api/capabilities",
        "/api/pipeline/kinds",
        "/api/pipeline/validate");

    /**
     * Read-only analyses that are cheap in outcome but NOT in cost: both fully parse an arbitrary
     * uploaded document, and both route office uploads through LibreOffice ({@code routeToPdf} →
     * {@code OfficeGuard}). They stay free of the daily quota — a user inspecting a file should not
     * burn an operation — but they must be metered by the general rate bucket, otherwise a caller
     * can pin the load-guard and both soffice permits indefinitely at zero cost.
     */
    private static final Set<String> METERED_CHEAP = Set.of(
        "/api/metadata/read",
        "/api/form-fields");

    /**
     * Expensive endpoints: subject to the heavy rate bucket and the load-guard (concurrency /
     * in-flight-byte / processing-timeout) wrapper. This is every operation endpoint — each one
     * parses and re-renders arbitrary PDFs, so all must run under the anti-OOM/timeout guard.
     * Only {@link #CHEAP} and {@link #METERED_CHEAP} are excluded.
     */
    private static final Set<String> HEAVY = Set.of(
        "/api/compress",
        "/api/merge",
        "/api/extract",
        "/api/rotate",
        "/api/arrange",
        "/api/to-images",
        "/api/to-pdf",
        "/api/to-text",
        "/api/protect",
        "/api/unlock",
        "/api/metadata",
        "/api/watermark",
        "/api/crop",
        "/api/redact",
        "/api/nup",
        "/api/sign",
        "/api/auto-redact",
        "/api/ocr",
        "/api/page-marks",
        "/api/gdpr-scan",
        "/api/gdpr-scan-batch",
        "/api/pipeline/run",
        "/api/render");

    /** Operation endpoints whose successful requests consume a free-tier daily quota unit. */
    private static final Set<String> QUOTA_OPS = Set.of(
        "/api/merge",
        "/api/extract",
        "/api/compress",
        "/api/rotate",
        "/api/arrange",
        "/api/to-pdf",
        "/api/protect",
        "/api/unlock",
        "/api/metadata",
        "/api/watermark",
        "/api/crop",
        "/api/redact",
        "/api/nup",
        "/api/sign",
        "/api/auto-redact",
        "/api/ocr",
        "/api/page-marks",
        "/api/gdpr-scan",
        "/api/gdpr-scan-batch",
        "/api/to-images",
        "/api/to-text",
        "/api/pipeline/run");

    /** The request's normalised path (trailing slash stripped, except the root). */
    public static String path(HttpServletRequest req) {
        String p = req.getRequestURI();
        if (p == null || p.isEmpty()) return "/";
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    public static boolean isApi(String path) {
        return path != null && (path.equals("/api") || path.startsWith("/api/"));
    }

    /**
     * True for the catalog / no-upload endpoints that are exempt from BOTH the general rate bucket
     * and the daily quota. Deliberately narrow: an endpoint that opens an uploaded file is never
     * "cheap" in this sense — see {@link #isMeteredCheap(String)}.
     */
    public static boolean isCheap(String path) {
        return CHEAP.contains(path);
    }

    /**
     * True for read-only analyses that are rate-limited like an operation but never consume a
     * daily quota unit and never run under the heavy guard.
     */
    public static boolean isMeteredCheap(String path) {
        return METERED_CHEAP.contains(path);
    }

    public static boolean isHeavy(String path) {
        return HEAVY.contains(path);
    }

    public static boolean isQuotaOp(String path) {
        return QUOTA_OPS.contains(path);
    }

    /**
     * True when the general rate bucket should meter this request: any {@code /api/**} path that is
     * not on the narrow free allow-list. Covers HEAVY and METERED_CHEAP alike.
     */
    public static boolean isMetered(String path) {
        return isApi(path) && !isCheap(path);
    }

    /**
     * True when the path is accounted for in one of the three tiers. A POST mapping that is
     * classified nowhere would run unmetered, unguarded and unquotaed — the classification test
     * fails on exactly this.
     */
    public static boolean isClassified(String path) {
        return isHeavy(path) || isCheap(path) || isMeteredCheap(path);
    }
}
