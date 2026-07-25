package com.pdfconduit.core.service;

import java.util.List;

/**
 * The result of a partial-tolerant MAP batch ({@link MemoryOperations#mapPartial}): everything that
 * was produced, plus every input that failed.
 *
 * <p>{@code failures} is empty for a fully successful batch — the ordinary case — so a caller can
 * treat a non-empty list as "warn the user, name the files" without changing the success path. An
 * outcome is never both empty and failed: when <em>every</em> input fails, {@code mapPartial} throws
 * instead of returning an empty outcome, so a batch that produced nothing is still an error.
 *
 * @param outputs  results in input order (a multi-output op contributes several per input)
 * @param failures the inputs that could not be processed, in input order
 */
public record BatchOutcome(List<NamedBytes> outputs, List<BatchFailure> failures) {

    /** True when some inputs succeeded and others failed — the "warn the user" case. */
    public boolean partial() {
        return !failures.isEmpty();
    }
}
