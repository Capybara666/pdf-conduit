package com.pdfconduit.web.support;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Set;

/**
 * The single source of truth for classifying {@code /api/**} endpoints for the hardening layer:
 * which are cheap (never rate-limited into uselessness nor counted against quota), which are
 * heavy (bounded by the concurrency / in-flight-byte / timeout guard and the heavy rate bucket),
 * and which are quota-counting operations. Keeping this in one place stops the rate-limit filter,
 * the quota interceptor and the controllers from drifting apart.
 */
public final class Endpoints {

    private Endpoints() {}

    /** Read-only / catalog endpoints: not metered by the general rate bucket, not counted against quota. */
    private static final Set<String> CHEAP = Set.of(
        "/api/health",
        "/api/operations",
        "/api/pipeline/kinds",
        "/api/pipeline/validate",
        "/api/metadata/read",
        "/api/page-marks");

    /**
     * Expensive endpoints: subject to the heavy rate bucket and the load-guard (concurrency /
     * in-flight-byte / processing-timeout) wrapper. This is every operation endpoint — each one
     * parses and re-renders arbitrary PDFs, so all must run under the anti-OOM/timeout guard.
     * Only the read-only / catalog endpoints in {@link #CHEAP} (and metadata/read) are excluded.
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

    public static boolean isCheap(String path) {
        return CHEAP.contains(path);
    }

    public static boolean isHeavy(String path) {
        return HEAVY.contains(path);
    }

    public static boolean isQuotaOp(String path) {
        return QUOTA_OPS.contains(path);
    }

    /** True when the general rate bucket should meter this request (any non-cheap {@code /api/**}). */
    public static boolean isMetered(String path) {
        return isApi(path) && !isCheap(path);
    }
}
