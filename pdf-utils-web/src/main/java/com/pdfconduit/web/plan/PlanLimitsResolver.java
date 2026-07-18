package com.pdfconduit.web.plan;

import com.pdfconduit.web.principal.RequestPrincipal;

/**
 * Resolves the {@link PlanLimits} in force for a request. The single implementation today
 * ({@link FreePlanLimitsResolver}) returns one constant FREE plan for every principal; a later paid
 * tier resolves a different plan per {@link RequestPrincipal} — the guards that consume the result
 * do not change.
 */
public interface PlanLimitsResolver {

    /** Entitlements for a specific caller (today: always the FREE plan). */
    PlanLimits resolve(RequestPrincipal principal);

    /**
     * Entitlements when no principal is in scope — used by the service layer, which guards by value
     * without a request in hand. Today identical to {@link #resolve(RequestPrincipal)} (one plan).
     */
    default PlanLimits resolveDefault() {
        return resolve(null);
    }
}
