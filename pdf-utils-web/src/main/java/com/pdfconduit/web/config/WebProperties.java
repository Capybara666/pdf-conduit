package com.pdfconduit.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.util.List;

/**
 * Bound from {@code pdfconduit.web.*} in application.yml (env-overridable). The stateless
 * backend keeps no work directory — every PDF/image operation runs in memory — so the only
 * disk-adjacent knobs left are the LibreOffice binary (the documented office exception) and
 * whether office conversion is enabled at all.
 *
 * <p>Beyond that, this record carries the public-SaaS hardening knobs: per-IP rate limiting,
 * a free-tier daily quota + per-file caps, an anti-OOM concurrency / in-flight-byte guard, a
 * processing timeout, and a PDF page-count ceiling. Every nested record normalises its own
 * nulls in a compact constructor so consumers never see a {@code null} sub-value.
 *
 * @param sofficePath        explicit LibreOffice {@code soffice} path; blank ⇒ auto-detect
 * @param maxFilesPerRequest hard guardrail on how many files a single request may upload
 * @param office             office-conversion toggle + concurrency/timeout (the sole disk exception)
 * @param cors               CORS configuration for the Angular frontend
 * @param ratelimit          per-IP token-bucket rate limiting
 * @param quota              free-tier daily quota + per-file caps
 * @param concurrency        heavy-op concurrency + in-flight-byte anti-OOM guard
 * @param processing         per-operation processing timeout
 * @param pdf                PDF-bomb guards (max page count)
 * @param render             raster-render guards (max DPI + total output-pixel ceiling)
 * @param trustedProxies     CIDRs of proxies allowed to set {@code X-Forwarded-For} (client-IP trust)
 */
@ConfigurationProperties("pdfconduit.web")
public record WebProperties(String sofficePath, Integer maxFilesPerRequest, Office office, Ocr ocr,
                            Cors cors, RateLimit ratelimit, Quota quota, Concurrency concurrency,
                            Processing processing, Pdf pdf, Render render, List<String> trustedProxies) {

    /** Loopback + RFC-1918 private ranges (covers the docker-compose bridge subnet) trusted by default. */
    private static final List<String> DEFAULT_TRUSTED_PROXIES =
        List.of("127.0.0.1/32", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16");

    public WebProperties {
        if (maxFilesPerRequest == null || maxFilesPerRequest < 1) maxFilesPerRequest = 50;
        if (office == null) office = new Office(true, null, null);
        if (ocr == null) ocr = new Ocr(false, null, null, null, null);
        if (cors == null || cors.allowedOrigins() == null || cors.allowedOrigins().isEmpty()) {
            cors = new Cors(List.of("http://localhost:4200"));
        }
        if (ratelimit == null) ratelimit = new RateLimit(true, null, null, null);
        if (quota == null) quota = new Quota(true, null, null, null);
        if (concurrency == null) concurrency = new Concurrency(null, null);
        if (processing == null) processing = new Processing(null);
        if (pdf == null) pdf = new Pdf(null);
        if (render == null) render = new Render(null, null);
        if (trustedProxies == null || trustedProxies.isEmpty()) trustedProxies = DEFAULT_TRUSTED_PROXIES;
    }

    /**
     * Office/document (`.docx`, `.xlsx`, …) conversion settings. {@code enabled} gates whether
     * office uploads are accepted at all; {@code maxConcurrent}/{@code timeoutSeconds} bound how
     * many LibreOffice conversions run at once and how long each may take (best-effort).
     */
    public record Office(boolean enabled, Integer maxConcurrent, Integer timeoutSeconds) {
        public Office {
            if (maxConcurrent == null || maxConcurrent < 1) maxConcurrent = 2;
            if (timeoutSeconds == null || timeoutSeconds < 1) timeoutSeconds = 90;
        }
    }

    /**
     * OCR (searchable-PDF) settings, powered by the external {@code tesseract} binary. {@code
     * enabled} gates whether the {@code /api/ocr} endpoint accepts work at all (default {@code
     * false} — OCR is opt-in and requires Tesseract to be installed); {@code tesseractPath} is an
     * optional explicit binary path (blank ⇒ auto-detect); {@code maxConcurrent}/{@code
     * timeoutSeconds} bound how many OCR jobs run at once and how long each may take; {@code
     * languages} is the default tesseract language set (e.g. {@code eng} or {@code eng+deu}).
     */
    public record Ocr(boolean enabled, String tesseractPath, Integer maxConcurrent,
                      Integer timeoutSeconds, String languages) {
        public Ocr {
            if (maxConcurrent == null || maxConcurrent < 1) maxConcurrent = 1;
            if (timeoutSeconds == null || timeoutSeconds < 1) timeoutSeconds = 120;
            if (languages == null || languages.isBlank()) languages = "eng";
        }
    }

    /** Cross-origin settings so the Angular dev server (default {@code http://localhost:4200}) can call the API. */
    public record Cors(List<String> allowedOrigins) {}

    /**
     * Per-IP token-bucket rate limiting. A GENERAL bucket ({@code requestsPerMinute} refill,
     * {@code burst} capacity) applies to every metered {@code /api/**} endpoint; a HEAVY bucket
     * ({@code heavyPerMinute}) applies additionally to expensive endpoints.
     */
    public record RateLimit(boolean enabled, Integer requestsPerMinute, Integer heavyPerMinute, Integer burst) {
        public RateLimit {
            if (requestsPerMinute == null || requestsPerMinute < 1) requestsPerMinute = 40;
            if (heavyPerMinute == null || heavyPerMinute < 1) heavyPerMinute = 10;
            if (burst == null || burst < 1) burst = 15;
        }
    }

    /**
     * Free-tier limits. {@code dailyOperations} successful operations per IP per calendar day;
     * {@code freeMaxFileSize} per-uploaded-file ceiling; {@code freeMaxFiles} per-request file
     * ceiling. Stricter than the absolute multipart caps, which remain the hard ceiling.
     */
    public record Quota(boolean enabled, Integer dailyOperations, DataSize freeMaxFileSize, Integer freeMaxFiles) {
        public Quota {
            if (dailyOperations == null || dailyOperations < 1) dailyOperations = 60;
            if (freeMaxFileSize == null || freeMaxFileSize.toBytes() < 1) freeMaxFileSize = DataSize.ofMegabytes(25);
            if (freeMaxFiles == null || freeMaxFiles < 1) freeMaxFiles = 15;
        }
    }

    /**
     * Anti-OOM guard for heavy operations: at most {@code maxHeavyOps} run concurrently and the
     * summed bytes of in-flight heavy requests may not exceed {@code maxInFlightBytes}.
     */
    public record Concurrency(Integer maxHeavyOps, Long maxInFlightBytes) {
        public Concurrency {
            if (maxHeavyOps == null || maxHeavyOps < 1) maxHeavyOps = 4;
            if (maxInFlightBytes == null || maxInFlightBytes < 1) maxInFlightBytes = 536870912L; // 512 MiB
        }
    }

    /** Per-operation processing timeout (seconds) for heavy work run on the bounded executor. */
    public record Processing(Integer timeoutSeconds) {
        public Processing {
            if (timeoutSeconds == null || timeoutSeconds < 1) timeoutSeconds = 60;
        }
    }

    /** PDF-bomb guard: reject any PDF whose page count exceeds {@code maxPages}. */
    public record Pdf(Integer maxPages) {
        public Pdf {
            if (maxPages == null || maxPages < 1) maxPages = 3000;
        }
    }

    /**
     * Raster-render guards for the page → image endpoints (render / to-images / redact). {@code
     * maxDpi} caps the requested DPI (a request above it is rejected, not silently clamped), and
     * {@code maxOutputPixels} rejects any single page whose rendered pixel area (page inches × DPI,
     * squared) would exceed the ceiling — the two together bound a render's memory footprint so a
     * single request cannot OOM the JVM.
     */
    public record Render(Integer maxDpi, Long maxOutputPixels) {
        public Render {
            if (maxDpi == null || maxDpi < 1) maxDpi = 300;
            if (maxOutputPixels == null || maxOutputPixels < 1) maxOutputPixels = 60_000_000L; // ~60 MP/page
        }
    }

    public boolean hasSofficePath() {
        return sofficePath != null && !sofficePath.isBlank();
    }

    public boolean officeEnabled() {
        return office != null && office.enabled();
    }

    public boolean ocrEnabled() {
        return ocr != null && ocr.enabled();
    }

    public boolean hasTesseractPath() {
        return ocr != null && ocr.tesseractPath() != null && !ocr.tesseractPath().isBlank();
    }
}
