package com.pdfconduit.web.cost;

/**
 * What one request is expected to cost the heap, in bytes, worked out from its declared
 * {@link CostSpec} <em>before</em> any of it is allocated. {@link com.pdfconduit.web.guard.LoadGuard}
 * admits against this: a request that cannot fit at all is refused outright, one that cannot fit
 * <em>right now</em> waits its turn or is shed.
 *
 * <p>Split into four terms because they are four different things an operator can act on, and
 * because the split is what makes the number auditable rather than a magic constant.
 *
 * @param inputBytes        the uploads, held for the life of the request
 * @param intermediateBytes documents a multi-stage run keeps alive between stages (pipeline nodes)
 * @param resultBytes       the results, already multiplied by the copies that coexist while the
 *                          response is assembled (the result set, the ZIP buffer, the response body)
 * @param workingBytes      transient working set: parsed documents, one page's decoded raster
 */
public record WorkEstimate(long inputBytes, long intermediateBytes, long resultBytes,
                           long workingBytes) {

    public WorkEstimate {
        inputBytes = Math.max(0, inputBytes);
        intermediateBytes = Math.max(0, intermediateBytes);
        resultBytes = Math.max(0, resultBytes);
        workingBytes = Math.max(0, workingBytes);
    }

    /** The peak heap this request is expected to occupy — the number admission is decided on. */
    public long peakBytes() {
        return inputBytes + intermediateBytes + resultBytes + workingBytes;
    }

    /** A rounded megabyte figure for user-facing messages (never reports "0 MB"). */
    public long peakMegabytes() {
        return Math.max(1, Math.round(peakBytes() / (1024.0 * 1024.0)));
    }
}
