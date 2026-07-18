package com.pdfconduit.web.guard;

import com.pdfconduit.core.convert.DocumentConverter;
import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.web.config.WebProperties;
import com.pdfconduit.web.error.ProcessingTimeoutException;
import com.pdfconduit.web.error.ServerBusyException;
import com.pdfconduit.web.observability.WebMetrics;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A web-level gate around LibreOffice (office/text) conversion. Core already serialises {@code
 * soffice} behind a global lock, but that lets request threads pile up waiting; this permit
 * ({@code office.max-concurrent}) sheds excess conversions as 503 instead, and applies a
 * best-effort {@code office.timeout-seconds} deadline. Non-office conversions (PDF passthrough,
 * image → PDF) run directly with no gating.
 */
@Component
public class OfficeGuard {

    private static final long ACQUIRE_TIMEOUT_MS = 1_000;

    private final Semaphore permits;
    private final int maxConcurrent;
    private final long timeoutSeconds;
    private final ExecutorService executor;
    private final WebMetrics metrics;

    public OfficeGuard(WebProperties props, WebMetrics metrics) {
        this.metrics = metrics;
        this.maxConcurrent = props.office().maxConcurrent();
        this.permits = new Semaphore(props.office().maxConcurrent(), true);
        // Never let an office conversion outlive the request's own processing deadline: on timeout
        // we interrupt the core conversion thread, which force-kills the soffice process.
        this.timeoutSeconds = Math.min(props.office().timeoutSeconds(), props.processing().timeoutSeconds());
        ThreadFactory tf = new ThreadFactory() {
            private final AtomicLong n = new AtomicLong();
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "office-conv-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        this.executor = Executors.newCachedThreadPool(tf);
    }

    /** A conversion producing PDF bytes; throws the checked types core conversion can raise. */
    @FunctionalInterface
    public interface Conversion {
        byte[] run() throws IOException, PdfOperationException;
    }

    /**
     * Runs {@code conversion}, gating it behind the office permit + timeout only when {@code
     * filename} classifies as an office/document input. PDFs and images pass straight through.
     */
    public byte[] run(String filename, Conversion conversion) throws IOException, PdfOperationException {
        if (!isOffice(filename)) {
            return conversion.run();
        }
        boolean acquired;
        try {
            acquired = permits.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServerBusyException("Server busy (office conversion), try again shortly.");
        }
        if (!acquired) throw new ServerBusyException("Server busy (office conversion), try again shortly.");

        metrics.officeConversion();
        Future<byte[]> future = executor.submit((Callable<byte[]>) conversion::run);
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ProcessingTimeoutException();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ProcessingTimeoutException();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof PdfOperationException pe) throw pe;
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new PdfOperationException("Office conversion failed: "
                + (cause == null ? "unknown error" : cause.getMessage()), cause);
        } finally {
            permits.release();
        }
    }

    private static boolean isOffice(String filename) {
        return DocumentConverter.classify(Path.of(filename)) == DocumentConverter.Kind.OFFICE;
    }

    // ---- read-only state accessors for observability (health + metrics gauge) ----

    /** Office-conversion permits currently free (0 ⇒ all LibreOffice slots busy). */
    public int availablePermits() {
        return permits.availablePermits();
    }

    /** Configured maximum number of concurrent office conversions. */
    public int maxConcurrent() {
        return maxConcurrent;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
