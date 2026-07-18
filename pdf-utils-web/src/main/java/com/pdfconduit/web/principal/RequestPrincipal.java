package com.pdfconduit.web.principal;

/**
 * Identifies the caller for rate-limiting and quota metering. Today the backend is anonymous and a
 * principal is nothing more than the resolved client IP, but every hardening layer keys off
 * {@link #id()} rather than a raw IP string so a later account-based principal (an authenticated
 * user / API key) is a drop-in: give the account its own {@code id()} and the same buckets, quota
 * counters and plan lookups apply unchanged.
 */
public interface RequestPrincipal {

    /** Stable, opaque key that identifies this caller for limiting and metering. */
    String id();
}
