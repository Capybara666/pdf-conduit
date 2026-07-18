package com.pdfconduit.web.quota;

/**
 * The counter backend behind the free-tier daily quota, keyed by a principal id (today the client
 * IP). Owns the per-day counting semantics — counters roll over at UTC midnight — but knows nothing
 * of the limit itself (that is a {@link com.pdfconduit.web.plan.PlanLimits} value). The single
 * implementation today ({@link InMemoryQuotaStore}) keeps counters in an in-process map; a later
 * Redis / shared-store implementation makes the quota correct across multiple instances with no
 * change to {@link QuotaService} or the interceptor that depend on this interface.
 */
public interface QuotaStore {

    /** Operations already counted for {@code key} today (after rolling over if the day changed). */
    long used(String key);

    /** Counts one successful operation for {@code key}. */
    void increment(String key);

    /** Epoch seconds of the next reset boundary (next UTC midnight). */
    long resetEpochSeconds();
}
