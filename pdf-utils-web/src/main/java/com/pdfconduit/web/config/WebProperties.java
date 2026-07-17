package com.pdfconduit.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Bound from {@code pdfconduit.web.*} in application.yml (env-overridable). The stateless
 * backend keeps no work directory — every PDF/image operation runs in memory — so the only
 * disk-adjacent knobs left are the LibreOffice binary (the documented office exception) and
 * whether office conversion is enabled at all.
 *
 * @param sofficePath        explicit LibreOffice {@code soffice} path; blank ⇒ auto-detect
 * @param maxFilesPerRequest guardrail on how many files a single request may upload
 * @param office             office-conversion toggle (the sole disk exception)
 * @param cors               CORS configuration for the Angular frontend
 */
@ConfigurationProperties("pdfconduit.web")
public record WebProperties(String sofficePath, Integer maxFilesPerRequest, Office office, Cors cors) {

    public WebProperties {
        if (maxFilesPerRequest == null || maxFilesPerRequest < 1) maxFilesPerRequest = 50;
        if (office == null) office = new Office(true);
        if (cors == null || cors.allowedOrigins() == null || cors.allowedOrigins().isEmpty()) {
            cors = new Cors(List.of("http://localhost:4200"));
        }
    }

    /** Whether office/document (`.docx`, `.xlsx`, …) uploads may be converted (needs LibreOffice + a temp dir). */
    public record Office(boolean enabled) {}

    /** Cross-origin settings so the Angular dev server (default {@code http://localhost:4200}) can call the API. */
    public record Cors(List<String> allowedOrigins) {}

    public boolean hasSofficePath() {
        return sofficePath != null && !sofficePath.isBlank();
    }

    public boolean officeEnabled() {
        return office != null && office.enabled();
    }
}
