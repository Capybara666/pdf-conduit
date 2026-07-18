package com.pdfconduit.web.guard;

import com.pdfconduit.core.exception.PdfOperationException;
import com.pdfconduit.web.config.WebProperties;
import com.pdfconduit.web.error.ProcessingTimeoutException;
import com.pdfconduit.web.error.ServerBusyException;
import com.pdfconduit.web.observability.WebMetrics;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.IOException;
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
 * A web-level gate around OCR (external {@code tesseract}) work, mirroring {@link OfficeGuard}.
 * OCR renders every page to a raster and shells out to Tesseract per page, so it is heavy and
 * spawns external processes; this permit ({@code ocr.max-concurrent}) sheds excess OCR jobs as 503
 * instead of piling request threads up, and applies a best-effort {@code ocr.timeout-seconds}
 * deadline (never longer than the request's own processing deadline). On timeout the OCR thread is
 * interrupted, which force-kills the running {@code tesseract} process.
 */
@Component
public class OcrGuard {

    private static final long ACQUIRE_TIMEOUT_MS = 1_000;

    private final Semaphore permits;
    private final int maxConcurrent;
    private final long timeoutSeconds;
    private final ExecutorService executor;
    private final WebMetrics metrics;

    public OcrGuard(WebProperties props, WebMetrics metrics) {
        this.metrics = metrics;
        this.maxConcurrent = props.ocr().maxConcurrent();
        this.permits = new Semaphore(props.ocr().maxConcurrent(), true);
        this.timeoutSeconds = Math.min(props.ocr().timeoutSeconds(), props.processing().timeoutSeconds());
        ThreadFactory tf = new ThreadFactory() {
            private final AtomicLong n = new AtomicLong();
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "ocr-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        this.executor = Executors.newCachedThreadPool(tf);
    }

    /** An OCR unit of work producing the searchable PDF bytes. */
    @FunctionalInterface
    public interface OcrTask {
        byte[] run() throws IOException, PdfOperationException;
    }

    /** Runs {@code task} under the OCR permit + timeout; sheds as 503 when saturated. */
    public byte[] run(OcrTask task) throws IOException, PdfOperationException {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServerBusyException("Server busy (OCR), try again shortly.");
        }
        if (!acquired) throw new ServerBusyException("Server busy (OCR), try again shortly.");

        metrics.ocrJob();
        Future<byte[]> future = executor.submit((Callable<byte[]>) task::run);
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
            throw new PdfOperationException("OCR failed: "
                + (cause == null ? "unknown error" : cause.getMessage()), cause);
        } finally {
            permits.release();
        }
    }

    /** OCR permits currently free (0 ⇒ all OCR slots busy). */
    public int availablePermits() {
        return permits.availablePermits();
    }

    /** Configured maximum number of concurrent OCR jobs. */
    public int maxConcurrent() {
        return maxConcurrent;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
