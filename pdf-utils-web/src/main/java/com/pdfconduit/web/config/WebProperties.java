package com.pdfconduit.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code pdfconduit.web.*} in application.yml (env-overridable).
 *
 * @param workDir            base directory for per-request temp workspaces; blank ⇒
 *                           {@code ${java.io.tmpdir}/pdfconduit-web}
 * @param sofficePath        explicit LibreOffice {@code soffice} path; blank ⇒ auto-detect
 * @param maxFilesPerRequest guardrail on how many files a single request may upload
 */
@ConfigurationProperties("pdfconduit.web")
public record WebProperties(String workDir, String sofficePath, Integer maxFilesPerRequest) {

    public WebProperties {
        if (maxFilesPerRequest == null || maxFilesPerRequest < 1) maxFilesPerRequest = 50;
    }

    public boolean hasSofficePath() {
        return sofficePath != null && !sofficePath.isBlank();
    }
}
