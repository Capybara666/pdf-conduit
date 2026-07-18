package com.pdfconduit.web.observability;

import com.pdfconduit.core.convert.DocumentConverter;
import com.pdfconduit.web.config.WebProperties;
import com.pdfconduit.web.guard.LoadGuard;
import com.pdfconduit.web.guard.OfficeGuard;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * A meaningful liveness/readiness signal for the backend, wired into actuator health on the
 * INTERNAL management port. Instead of a static "UP", it reflects the live admission-control state:
 *
 * <ul>
 *   <li><b>DOWN</b> — fully saturated on BOTH axes at once: no heavy-op permits free AND the
 *       in-flight-byte cap reached. The load guard is shedding every incoming heavy op (503);
 *       an orchestrator may briefly steer traffic away.</li>
 *   <li><b>DEGRADED</b> — saturated on one axis (no heavy permits, or the byte cap reached), or
 *       office conversion is enabled but LibreOffice ({@code soffice}) is not resolvable so office
 *       uploads will 415. The service still handles what it can.</li>
 *   <li><b>UP</b> — spare capacity on both axes.</li>
 * </ul>
 *
 * The bean name {@code saturation} makes it appear under {@code health.components.saturation}.
 * This is exposed on the internal port only; the public {@code GET /api/health} stays a simple,
 * stable liveness shape for nginx/Docker.
 */
@Component("saturation")
public class SaturationHealthIndicator implements HealthIndicator {

    /** Custom, informational status for a single-axis / office-missing degradation. */
    static final Status DEGRADED = new Status("DEGRADED");

    private final LoadGuard loadGuard;
    private final OfficeGuard officeGuard;
    private final boolean officeEnabled;

    public SaturationHealthIndicator(LoadGuard loadGuard, OfficeGuard officeGuard, WebProperties props) {
        this.loadGuard = loadGuard;
        this.officeGuard = officeGuard;
        this.officeEnabled = props.officeEnabled();
    }

    @Override
    public Health health() {
        int permits = loadGuard.availablePermits();
        int maxPermits = loadGuard.maxHeavyOps();
        long inFlight = loadGuard.inFlightBytes();
        long maxInFlight = loadGuard.maxInFlightBytes();
        int officePermits = officeGuard.availablePermits();
        boolean sofficeAvailable = DocumentConverter.sofficePath() != null;

        boolean permitsSaturated = permits <= 0;
        boolean bytesSaturated = inFlight >= maxInFlight;
        boolean officeMissing = officeEnabled && !sofficeAvailable;

        Status status;
        if (permitsSaturated && bytesSaturated) {
            status = Status.DOWN;
        } else if (permitsSaturated || bytesSaturated || officeMissing) {
            status = DEGRADED;
        } else {
            status = Status.UP;
        }

        return Health.status(status)
            .withDetail("heavyPermitsAvailable", permits)
            .withDetail("heavyPermitsMax", maxPermits)
            .withDetail("inFlightBytes", inFlight)
            .withDetail("maxInFlightBytes", maxInFlight)
            .withDetail("officeEnabled", officeEnabled)
            .withDetail("officePermitsAvailable", officePermits)
            .withDetail("sofficeAvailable", sofficeAvailable)
            .build();
    }
}
