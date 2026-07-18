package com.pdfconduit.web.config;

import com.pdfconduit.core.convert.DocumentConverter;
import com.pdfconduit.core.operations.PdfOcr;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Applies web configuration at startup. The backend is stateless and in-memory, so there is
 * no work directory to create — the only side effect is registering an explicit LibreOffice
 * path (if one is configured) with the shared core converter, used for the documented office
 * conversion exception.
 */
@Configuration
@EnableConfigurationProperties(WebProperties.class)
public class StartupConfig {

    private static final Logger log = LoggerFactory.getLogger(StartupConfig.class);

    private final WebProperties props;

    public StartupConfig(WebProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        if (props.hasSofficePath()) {
            DocumentConverter.setSofficeOverride(props.sofficePath());
            log.info("Using configured LibreOffice binary: {}", props.sofficePath());
        }
        // Bound core LibreOffice concurrency (every soffice invocation, incl. the to-text output-side
        // txt→docx pass) and cap its wall-clock timeout at the request processing deadline so a stuck
        // conversion is force-killed and can't outlive the request that started it.
        DocumentConverter.setMaxConcurrentConversions(props.office().maxConcurrent());
        DocumentConverter.setConversionTimeoutSeconds(
            Math.min(props.office().timeoutSeconds(), props.processing().timeoutSeconds()));

        // OCR (searchable PDF) via the external tesseract binary — same pattern as LibreOffice:
        // register an explicit binary path if configured, and cap the per-page timeout at the
        // request processing deadline so a stuck tesseract can't outlive its request.
        if (props.hasTesseractPath()) {
            PdfOcr.setTesseractOverride(props.ocr().tesseractPath());
            log.info("Using configured Tesseract binary: {}", props.ocr().tesseractPath());
        }
        PdfOcr.setTimeoutSeconds(
            Math.min(props.ocr().timeoutSeconds(), props.processing().timeoutSeconds()));

        log.info("PDF Conduit web backend started (stateless, in-memory; office conversion {}; OCR {}).",
            props.officeEnabled() ? "enabled" : "disabled",
            props.ocrEnabled() ? "enabled" : "disabled");
    }
}
