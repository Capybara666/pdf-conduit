package com.pdfconduit.web.guard;

import com.pdfconduit.core.exception.InvalidPageRangeException;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.pipeline.PipelineException;
import com.pdfconduit.web.config.WebProperties;
import com.pdfconduit.web.cost.CostModel;
import com.pdfconduit.web.cost.WorkEstimate;
import com.pdfconduit.web.error.OutputTooLargeException;
import com.pdfconduit.web.error.ProcessingTimeoutException;
import com.pdfconduit.web.error.ServerBusyException;
import com.pdfconduit.web.observability.WebMetrics;
import com.pdfconduit.web.plan.PlanLimits;
import com.pdfconduit.web.plan.RequestPlan;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Anti-OOM / anti-runaway admission control for heavy operations. Regardless of Tomcat's 200
 * request threads, at most {@code concurrency.max-heavy-ops} heavy operations run at once and the
 * summed <em>estimated cost</em> of everything in flight never exceeds
 * {@code concurrency.max-work-bytes}. Each admitted operation runs on a bounded executor with a
 * {@code processing.timeout-seconds} deadline; on timeout the client is shed (503) and the future
 * cancelled.
 *
 * <p><b>Admission is decided on an estimate, before anything is allocated.</b> Every request
 * arrives with a {@link WorkEstimate} derived from the cost its endpoint (or, for a pipeline, its
 * graph) declares — see {@link CostModel}. Two outcomes follow, and they mean different things:
 * <ul>
 *   <li>the estimate exceeds the whole pool ⇒ this request can never run here, so it is refused
 *       outright with 422 {@code output_too_large} in milliseconds — retrying will not help;</li>
 *   <li>the estimate exceeds what is <em>free</em> right now ⇒ 503 {@code server_busy}, the same
 *       transient shed as a saturated permit.</li>
 * </ul>
 * Counting the estimate rather than a slot is what stops the per-request budget and the slot count
 * from multiplying: four permits no longer imply four times the largest affordable request.
 *
 * <p>PDFBox work may not honour thread interruption immediately, so {@link Future#cancel(boolean)}
 * only guarantees the <em>caller</em> is released — a stuck task can keep running. Crucially the
 * concurrency permit and in-flight-byte reservation are released only when the task <em>actually
 * completes</em> (not when the caller times out), and the executor is bounded to {@code
 * max-heavy-ops} threads. So a runaway task keeps holding its slot: new admissions are shed (503)
 * until it finishes, and stuck work can never pile up beyond the fixed thread/permit count.
 */
@Component
public class LoadGuard {

    private static final Logger log = LoggerFactory.getLogger(LoadGuard.class);

    /** Max time to wait for a concurrency permit before shedding the request as 503. */
    private static final long ACQUIRE_TIMEOUT_MS = 2_000;

    private final Semaphore permits;
    private final AtomicLong inFlightBytes = new AtomicLong();
    private final long maxWorkBytes;
    private final int maxHeavyOps;
    private final long timeoutSeconds;
    private final ExecutorService executor;
    private final WebMetrics metrics;
    private final RequestPlan requestPlan;
    private final CostModel costs;

    public LoadGuard(WebProperties props, WebMetrics metrics, RequestPlan requestPlan,
                     CostModel costs) {
        this.metrics = metrics;
        this.requestPlan = requestPlan;
        this.costs = costs;
        this.permits = new Semaphore(props.concurrency().maxHeavyOps(), true);
        this.maxWorkBytes = props.concurrency().maxWorkBytes();
        this.maxHeavyOps = props.concurrency().maxHeavyOps();
        this.timeoutSeconds = props.processing().timeoutSeconds();
        ThreadFactory tf = new ThreadFactory() {
            private final AtomicLong n = new AtomicLong();
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "heavy-op-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        // Bounded to the permit count: a runaway task ties up one thread AND one permit until it
        // finishes, so stuck work can never spawn threads without limit.
        this.executor = Executors.newFixedThreadPool(props.concurrency().maxHeavyOps(), tf);
    }

    /** A unit of heavy work, throwing the checked exceptions the controllers already declare. */
    @FunctionalInterface
    public interface HeavyTask<T> {
        T get() throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException;
    }

    /**
     * Admits and runs a heavy operation, estimating its cost from the endpoint being handled. The
     * estimate is derived here rather than passed in, so a new endpoint cannot reach the executor
     * without one — the caller only has to say how many bytes it uploaded.
     *
     * @param inputBytes summed size of this request's already-read uploads
     */
    public <T> T execute(long inputBytes, HeavyTask<T> task)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        return execute(costs.forCurrentRequest(Math.max(0, inputBytes)), task);
    }

    /**
     * Admits and runs a heavy operation under the concurrency + work-byte + timeout guards, against
     * an estimate the caller worked out itself. Used by {@code /api/pipeline/run}, whose cost is a
     * property of the client-supplied graph rather than of its uploads.
     *
     * @param estimate what this request is expected to cost the heap (see {@link CostModel})
     * @throws OutputTooLargeException    if the estimate exceeds the whole work-byte pool (422)
     * @throws ServerBusyException        if no permit is free or the pool has no room right now
     * @throws ProcessingTimeoutException if the work exceeds the processing timeout
     */
    public <T> T execute(WorkEstimate estimate, HeavyTask<T> task)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        long bytes = estimate.peakBytes();
        // Refused before a permit is taken and before a single byte is allocated: a request that
        // cannot fit even on an idle server is a property of the request, not of the moment.
        if (maxWorkBytes > 0 && bytes > maxWorkBytes) {
            throw new OutputTooLargeException(
                "This request would need about " + estimate.peakMegabytes()
                + " MB of memory, more than this server commits to one request ("
                + megabytes(maxWorkBytes) + " MB). Use fewer or smaller files, fewer pipeline "
                + "steps, or a lower DPI.");
        }
        boolean acquired;
        try {
            acquired = permits.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServerBusyException();
        }
        if (!acquired) {
            metrics.loadShed();
            throw new ServerBusyException();
        }

        // The permit + byte reservation are released exactly once, when the task actually finishes
        // (see releaseOnce, wired to the future's completion) — NOT in a caller-side finally. A
        // timed-out caller returns 503 while the task keeps its slot until it truly ends.
        AtomicBoolean released = new AtomicBoolean();
        Runnable releaseOnce = () -> {
            if (released.compareAndSet(false, true)) {
                inFlightBytes.addAndGet(-bytes);
                permits.release();
            }
        };

        long now = inFlightBytes.addAndGet(bytes);
        if (maxWorkBytes > 0 && now > maxWorkBytes) {
            releaseOnce.run();  // nothing submitted yet; give the reservation straight back
            metrics.loadShed();
            throw new ServerBusyException("Server busy (memory pressure), try again shortly.");
        }

        // The caller's entitlements are resolved HERE, on the request thread, and carried onto the
        // worker: the guards inside the task read their ceilings from the resolved plan, and the
        // request object itself must not be touched off-thread (a timed-out caller is shed while
        // the task runs on, by which point Tomcat may have recycled the request).
        PlanLimits plan = requestPlan.current();
        CompletableFuture<T> result = new CompletableFuture<>();
        Future<?> running = executor.submit(() -> {
            RequestPlan.bind(plan);
            try {
                result.complete(task.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            } finally {
                RequestPlan.unbind();   // pooled threads outlive the request
            }
        });
        // Release the slot the moment the underlying work terminates (success, failure, or a late
        // interrupt), regardless of whether the caller already timed out and walked away.
        result.whenComplete((r, e) -> releaseOnce.run());

        try {
            return result.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            running.cancel(true);  // best-effort interrupt; slot frees only once the task really ends
            throw new ProcessingTimeoutException();
        } catch (InterruptedException e) {
            running.cancel(true);
            Thread.currentThread().interrupt();
            throw new ProcessingTimeoutException();
        } catch (ExecutionException e) {
            throw rethrow(e.getCause());
        }
    }

    /** Unwraps an executor failure back into the checked type the caller declared. */
    private static RuntimeException rethrow(Throwable cause)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        switch (cause) {
            case IOException e -> throw e;
            case PdfOperationException e -> throw e;
            case InvalidPageRangeException e -> throw e;
            case PipelineException e -> throw e;
            case RuntimeException e -> throw e;
            case Error e -> throw e;
            case null -> throw new IllegalStateException("Heavy task failed with no cause");
            default -> throw new IllegalStateException("Heavy task failed", cause);
        }
    }

    // ---- read-only state accessors for observability (health + metrics gauges) ----
    // Additive only; they report live counters and never alter admission behaviour.

    /** Heavy-op permits currently free (0 ⇒ all heavy slots busy, new heavy ops are being shed). */
    public int availablePermits() {
        return permits.availablePermits();
    }

    /** Configured maximum number of concurrent heavy operations. */
    public int maxHeavyOps() {
        return maxHeavyOps;
    }

    /** Summed estimated cost of the heavy requests currently in flight (reserved against the pool). */
    public long inFlightBytes() {
        return inFlightBytes.get();
    }

    /** The work-byte pool; requests that would push past it are shed as 503. */
    public long maxWorkBytes() {
        return maxWorkBytes;
    }

    private static long megabytes(long value) {
        return Math.max(1, Math.round(value / (1024.0 * 1024.0)));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        log.info("LoadGuard executor shut down.");
    }
}
