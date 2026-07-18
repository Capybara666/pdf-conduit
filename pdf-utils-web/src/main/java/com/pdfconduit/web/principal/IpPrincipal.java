package com.pdfconduit.web.principal;

/**
 * The sole {@link RequestPrincipal} today: an anonymous caller keyed by the client IP that
 * {@link com.pdfconduit.web.support.ClientIp} resolved. {@link #id()} is simply that IP, so it is
 * behaviourally identical to the raw-IP keys the guards used before the principal seam existed.
 */
public record IpPrincipal(String ip) implements RequestPrincipal {

    @Override
    public String id() {
        return ip;
    }
}
