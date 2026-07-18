package com.pdfconduit.web.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The custom SaaS-signal counters, registered once against Micrometer and incremented at the
 * points where each event actually happens (kept deliberately thin so the guards/filters that
 * call it stay non-invasive). Gauges (in-flight bytes, available heavy permits, office permits)
 * are polled from the live guard state by {@link GuardGauges} instead of being counters here.
 *
 * <p>Exposed on the internal management port via {@code /actuator/prometheus} and {@code /metrics}:
 * <ul>
 *   <li>{@code pdfconduit.load.shed} — heavy-op admissions shed as 503 by the LoadGuard;</li>
 *   <li>{@code pdfconduit.quota.exhausted} — requests rejected 429 for a spent daily free quota;</li>
 *   <li>{@code pdfconduit.ratelimit.rejected} — requests rejected 429 by the per-IP rate limiter;</li>
 *   <li>{@code pdfconduit.office.conversions} — LibreOffice (office/document) conversions started.</li>
 * </ul>
 */
@Component
public class WebMetrics {

    private final Counter loadShed;
    private final Counter quotaExhausted;
    private final Counter rateLimited;
    private final Counter officeConversions;
    private final Counter ocrJobs;

    public WebMetrics(MeterRegistry registry) {
        this.loadShed = Counter.builder("pdfconduit.load.shed")
            .description("Heavy operations shed (HTTP 503) by the load guard (no permit or byte cap reached)")
            .register(registry);
        this.quotaExhausted = Counter.builder("pdfconduit.quota.exhausted")
            .description("Requests rejected (HTTP 429) because the caller's daily free quota was spent")
            .register(registry);
        this.rateLimited = Counter.builder("pdfconduit.ratelimit.rejected")
            .description("Requests rejected (HTTP 429) by the per-IP token-bucket rate limiter")
            .register(registry);
        this.officeConversions = Counter.builder("pdfconduit.office.conversions")
            .description("LibreOffice office/document conversions started")
            .register(registry);
        this.ocrJobs = Counter.builder("pdfconduit.ocr.jobs")
            .description("OCR (searchable-PDF, tesseract) jobs started")
            .register(registry);
    }

    public void loadShed() {
        loadShed.increment();
    }

    public void quotaExhausted() {
        quotaExhausted.increment();
    }

    public void rateLimited() {
        rateLimited.increment();
    }

    public void officeConversion() {
        officeConversions.increment();
    }

    public void ocrJob() {
        ocrJobs.increment();
    }
}
