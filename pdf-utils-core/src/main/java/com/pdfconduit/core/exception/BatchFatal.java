package com.pdfconduit.core.exception;

/**
 * Marks a {@link PdfOperationException} that describes the <em>request</em>, not one of its files,
 * and must therefore fail the whole batch instead of being tolerated as that file's own problem.
 *
 * <p>{@link com.pdfconduit.core.service.MemoryOperations#mapPartial} keeps a MAP batch alive when a
 * single input turns out to be unusable (password-protected, damaged, over the page cap): the good
 * results come back and the bad file is named. That is right for a per-file defect and wrong for a
 * per-request ceiling — a host's aggregate output budget ("everything this request would return is
 * too big") is a property of the batch as a whole, so swallowing it would hand the user a partial
 * ZIP plus a puzzling "failure" for a file that is perfectly fine, and would let the run carry on
 * allocating exactly the memory the ceiling exists to bound.
 *
 * <p>Implemented by {@code com.pdfconduit.web.error.OutputTooLargeException}. Core defines only the
 * marker: what counts as "too big" stays entirely the host's policy.
 */
public interface BatchFatal {
}
