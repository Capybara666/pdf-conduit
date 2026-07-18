package com.pdfconduit.web.web;

import com.pdfconduit.core.service.OperationType;
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

    public InfoController(LoadGuard loadGuard) {
        this.loadGuard = loadGuard;
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

    @GetMapping("/operations")
    public List<OperationInfo> operations() {
        return Arrays.stream(OperationType.values()).map(OperationInfo::of).toList();
    }
}
