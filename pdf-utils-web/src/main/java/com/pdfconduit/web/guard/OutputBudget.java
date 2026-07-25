package com.pdfconduit.web.guard;

import com.pdfconduit.core.service.NamedBytes;
import com.pdfconduit.core.service.OutputSizeGuard;
import com.pdfconduit.web.config.WebProperties;
import com.pdfconduit.web.error.OutputTooLargeException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The per-request <b>aggregate</b> output ceiling — the guard that bounds {@code pages × files},
 * which the per-page render caps do not.
 *
 * <p>Why it exists: {@code render.max-output-pixels} only rejects a single page that is too big.
 * A request for many normal pages (one 800-page scan, or 15 files × 200 pages) passes every
 * per-file guard and then materialises one encoded image per page in a {@code List<byte[]>}, which
 * is zipped into a {@code ByteArrayOutputStream} and copied once more into the response body — up
 * to three concurrent full copies of the result in the heap. The container heap is ~1.15 GB, so a
 * request producing ~400 MB of images is already fatal, and {@code LoadGuard} deliberately does not
 * abort timed-out work, so the 503 at the processing deadline does not stop the allocation.
 *
 * <p>Two ceilings, both per request and both env-overridable:
 * <ul>
 *   <li>{@code pdfconduit.web.render.max-total-output-pixels} — summed pixel area of every page the
 *       request will actually rasterise, checked <em>before</em> anything is rendered.</li>
 *   <li>{@code pdfconduit.web.processing.max-total-output-bytes} — running ceiling on the result
 *       bytes accumulated so far, checked after every produced part (see {@link OutputSizeGuard})
 *       on the multi-output paths and after every completed file (see {@link Tally#commitResults})
 *       on the one-output-per-file MAP batches, so a pathological request dies early rather than
 *       at the end.</li>
 * </ul>
 * Both surface as 422 {@code output_too_large} with an actionable message.
 *
 * <p>Reads its ceilings from {@link WebProperties} like the other guards ({@code LoadGuard},
 * {@code OfficeGuard}, {@code OcrGuard}). A later per-plan version would resolve them from
 * {@code PlanLimits} alongside {@code maxOutputPixels}.
 */
@Component
public class OutputBudget {

    private final long maxTotalOutputPixels;
    private final long maxTotalOutputBytes;

    public OutputBudget(WebProperties props) {
        this.maxTotalOutputPixels = props.render().maxTotalOutputPixels();
        this.maxTotalOutputBytes = props.processing().maxTotalOutputBytes().toBytes();
    }

    /** The configured per-request render ceiling, in pixels. */
    public long maxTotalOutputPixels() {
        return maxTotalOutputPixels;
    }

    /** The configured per-request result ceiling, in bytes. */
    public long maxTotalOutputBytes() {
        return maxTotalOutputBytes;
    }

    /** A running tally for ONE request. Single-threaded by construction (one request, one tally). */
    public Tally tally() {
        return new Tally();
    }

    /** Single-file convenience: check one render's pixel total against the per-request ceiling. */
    public void checkPixels(long pixels) throws OutputTooLargeException {
        tally().addPixels(pixels);
    }

    /** Single-shot check of an already-materialised result set (defence in depth before zipping). */
    public void checkResultBytes(List<NamedBytes> results) throws OutputTooLargeException {
        long total = 0;
        for (NamedBytes r : results) total += r.data().length;
        tally().checkBytes(total);
    }

    /**
     * The per-request tally: pixels accumulate across the files of one request before any of them
     * is rendered, bytes accumulate as the parts are produced.
     */
    public final class Tally {

        private long pixels;
        private long bytes;

        private Tally() {}

        /** Adds a file's about-to-be-rendered pixel area; throws if the request total blows the budget. */
        public void addPixels(long filePixels) throws OutputTooLargeException {
            pixels += Math.max(0, filePixels);
            if (maxTotalOutputPixels > 0 && pixels > maxTotalOutputPixels) {
                throw new OutputTooLargeException(
                    "This request would render about " + megapixels(pixels) + " megapixels of images, "
                    + "more than this server produces in one request (" + megapixels(maxTotalOutputPixels)
                    + " megapixels). Choose fewer pages or files, or a lower DPI.");
            }
        }

        /** Throws if {@code totalBytes} produced so far by this request exceeds the budget. */
        public void checkBytes(long totalBytes) throws OutputTooLargeException {
            if (maxTotalOutputBytes > 0 && totalBytes > maxTotalOutputBytes) {
                throw new OutputTooLargeException(
                    "This request would produce more than " + megabytes(maxTotalOutputBytes)
                    + " MB of files, more than this server returns in one request. Choose fewer "
                    + "pages or files, or a lower DPI or quality.");
            }
        }

        /**
         * A running guard for one operation call, counting from what this request has produced so
         * far. Pass the parts back to {@link #commit(List)} once the call returns.
         */
        public OutputSizeGuard guard() {
            long base = bytes;
            return accumulated -> checkBytes(base + accumulated);
        }

        /** Folds a completed call's parts into the request total. */
        public void commit(List<byte[]> parts) throws OutputTooLargeException {
            for (byte[] p : parts) bytes += p.length;
            checkBytes(bytes);
        }

        /**
         * Folds a completed file's <em>named</em> results into the request total — the
         * one-output-per-file MAP batches (rotate, compress, extract-combine, …), which produce a
         * whole result per file rather than a stream of parts. Same ceiling, same 422; it simply
         * lands after each file instead of after each part, because that is the granularity at
         * which those operations allocate.
         */
        public void commitResults(List<NamedBytes> results) throws OutputTooLargeException {
            for (NamedBytes r : results) bytes += r.data().length;
            checkBytes(bytes);
        }

        /** Bytes produced by this request so far. */
        public long bytes() {
            return bytes;
        }
    }

    private static long megapixels(long pixels) {
        return Math.max(1, Math.round(pixels / 1_000_000.0));
    }

    private static long megabytes(long value) {
        return Math.max(1, Math.round(value / (1024.0 * 1024.0)));
    }
}
