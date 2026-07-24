package com.pdfconduit.web.web;

import com.pdfconduit.core.operations.PdfOcr;
import com.pdfconduit.core.service.OperationType;
import com.pdfconduit.web.config.WebProperties;
import com.pdfconduit.web.dto.CapabilitiesInfo;
import com.pdfconduit.web.dto.OperationInfo;
import com.pdfconduit.web.guard.LoadGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Non-operation endpoints: liveness and the operation catalog the UI can render from.
 *
 * <p>This {@code /api/health} is the PUBLIC liveness probe on port 8080 (nginx/Docker healthcheck
 * it). It keeps a simple, stable shape — {@code status} stays {@code "UP"} while the process is
 * serving — so the healthcheck parsing never breaks. It is additionally enriched with a
 * {@code saturated} flag reflecting the load guard, purely informational. The richer
 * DEGRADED/DOWN saturation signal lives on the internal management port's actuator health.
 */
@RestController
@RequestMapping("/api")
public class InfoController {

    private final LoadGuard loadGuard;
    private final WebProperties props;

    public InfoController(LoadGuard loadGuard, WebProperties props) {
        this.loadGuard = loadGuard;
        this.props = props;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        boolean saturated = loadGuard.availablePermits() <= 0
            || loadGuard.inFlightBytes() >= loadGuard.maxInFlightBytes();
        // Insertion-ordered so `status` is always the first field; nginx/Docker only read that.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("saturated", saturated);
        return body;
    }

    /**
     * The operation catalog (a bare array — its shape is consumed as-is by the frontend and the
     * generated client). Each entry carries {@code available}: {@code false} only for operations
     * this deployment cannot run — today just {@code ocr} when OCR is disabled.
     */
    @GetMapping("/operations")
    public List<OperationInfo> operations() {
        return Arrays.stream(OperationType.values())
            .map(type -> OperationInfo.of(type, isAvailable(type)))
            .toList();
    }

    /**
     * Server capability flags so the UI can adapt to this deployment: hide OCR when disabled,
     * drop office types from To&nbsp;PDF's accepted inputs when office conversion is off, and
     * offer the actually-installed OCR languages. The language list is discovered once (lazily,
     * cached by {@link PdfOcr}) — never per request — and is empty when OCR is disabled or the
     * {@code tesseract} binary is absent.
     */
    @GetMapping("/capabilities")
    public CapabilitiesInfo capabilities() {
        boolean ocrEnabled = props.ocrEnabled();
        List<String> ocrLanguages = ocrEnabled ? PdfOcr.installedLanguages() : List.of();
        return new CapabilitiesInfo(props.officeEnabled(), ocrEnabled, ocrLanguages);
    }

    private boolean isAvailable(OperationType type) {
        return type != OperationType.OCR || props.ocrEnabled();
    }
}
