package com.pdfconduit.web.guard;

import com.pdfconduit.core.exception.InvalidPageRangeException;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.core.pipeline.PipelineException;
import com.pdfconduit.web.config.WebProperties;
import com.pdfconduit.web.error.ProcessingTimeoutException;
import com.pdfconduit.web.error.ServerBusyException;
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
 * summed bytes of in-flight heavy requests never exceed {@code concurrency.max-in-flight-bytes}.
 * Each admitted operation runs on a bounded executor with a {@code processing.timeout-seconds}
 * deadline; on timeout the client is shed (503) and the future cancelled.
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
    private final long maxInFlightBytes;
    private final long timeoutSeconds;
    private final ExecutorService executor;

    public LoadGuard(WebProperties props) {
        this.permits = new Semaphore(props.concurrency().maxHeavyOps(), true);
        this.maxInFlightBytes = props.concurrency().maxInFlightBytes();
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
     * Admits and runs a heavy operation under the concurrency + in-flight-byte + timeout guards.
     *
     * @param requestBytes best-effort byte estimate of this request's inputs (for the OOM cap)
     * @throws ServerBusyException        if no permit is free or the byte cap would be exceeded
     * @throws ProcessingTimeoutException if the work exceeds the processing timeout
     */
    public <T> T execute(long requestBytes, HeavyTask<T> task)
            throws IOException, PdfOperationException, InvalidPageRangeException, PipelineException {
        long bytes = Math.max(0, requestBytes);
        boolean acquired;
        try {
            acquired = permits.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServerBusyException();
        }
        if (!acquired) throw new ServerBusyException();

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
        if (now > maxInFlightBytes) {
            releaseOnce.run();  // nothing submitted yet; give the reservation straight back
            throw new ServerBusyException("Server busy (memory pressure), try again shortly.");
        }

        CompletableFuture<T> result = new CompletableFuture<>();
        Future<?> running = executor.submit(() -> {
            try {
                result.complete(task.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
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

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        log.info("LoadGuard executor shut down.");
    }
}
