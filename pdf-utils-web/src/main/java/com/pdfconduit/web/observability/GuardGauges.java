package com.pdfconduit.web.observability;

import com.pdfconduit.web.guard.LoadGuard;
import com.pdfconduit.web.guard.OfficeGuard;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Registers Micrometer gauges that poll the live admission-control state of {@link LoadGuard} and
 * {@link OfficeGuard}. Kept separate from {@link WebMetrics} (which owns the counters) so the guards
 * can depend on {@code WebMetrics} for their counters without a dependency cycle — this binder is
 * the one that depends on the guards, and nothing depends on it.
 *
 * <p>Gauges (all on the internal management port):
 * <ul>
 *   <li>{@code pdfconduit.load.inflight.bytes} — estimated cost of heavy work currently reserved
 *       against the work-byte pool;</li>
 *   <li>{@code pdfconduit.load.permits.available} — free heavy-op permits (0 ⇒ shedding);</li>
 *   <li>{@code pdfconduit.office.permits.available} — free LibreOffice-conversion permits.</li>
 * </ul>
 */
@Component
public class GuardGauges {

    private final MeterRegistry registry;
    private final LoadGuard loadGuard;
    private final OfficeGuard officeGuard;

    public GuardGauges(MeterRegistry registry, LoadGuard loadGuard, OfficeGuard officeGuard) {
        this.registry = registry;
        this.loadGuard = loadGuard;
        this.officeGuard = officeGuard;
    }

    @PostConstruct
    void register() {
        Gauge.builder("pdfconduit.load.inflight.bytes", loadGuard, LoadGuard::inFlightBytes)
            .description("Estimated cost of heavy work in flight (reserved against the work-byte pool)")
            .baseUnit("bytes")
            .register(registry);
        Gauge.builder("pdfconduit.load.permits.available", loadGuard, g -> g.availablePermits())
            .description("Heavy-op permits currently free (0 = all heavy slots busy, shedding 503)")
            .register(registry);
        Gauge.builder("pdfconduit.office.permits.available", officeGuard, g -> g.availablePermits())
            .description("LibreOffice-conversion permits currently free")
            .register(registry);
    }
}
