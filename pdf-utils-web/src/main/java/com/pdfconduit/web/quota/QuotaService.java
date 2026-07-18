package com.pdfconduit.web.quota;

import org.springframework.stereotype.Component;

/**
 * Free-tier daily-quota facade. Counting is delegated to a {@link QuotaStore} (in-memory today,
 * a shared store later) so multi-instance correctness is a store swap, not a logic change. The
 * daily <em>limit</em> no longer lives here: it is a {@link com.pdfconduit.web.plan.PlanLimits}
 * value the caller passes in, so a later paid tier raises the limit without touching this class.
 * Only successful operations are counted (the interceptor increments in {@code afterCompletion} on
 * a 2xx), so failed requests never burn quota.
 */
@Component
public class QuotaService {

    private final QuotaStore store;

    public QuotaService(QuotaStore store) {
        this.store = store;
    }

    /** Operations already used by {@code key} today (after rolling over if the day changed). */
    public long used(String key) {
        return store.used(key);
    }

    /** Remaining free operations for {@code key} today, given the caller's {@code dailyLimit}. */
    public long remaining(String key, int dailyLimit) {
        return Math.max(0, dailyLimit - used(key));
    }

    /** True when {@code key} has already consumed its full {@code dailyLimit} for the day. */
    public boolean isExhausted(String key, int dailyLimit) {
        return used(key) >= dailyLimit;
    }

    /** Counts one successful operation for {@code key}. */
    public void increment(String key) {
        store.increment(key);
    }

    /** Epoch seconds of the next reset (next UTC midnight). */
    public long resetEpochSeconds() {
        return store.resetEpochSeconds();
    }
}
