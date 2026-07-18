package com.pdfconduit.web.principal;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the {@link RequestPrincipal} for an incoming request. The single implementation today
 * ({@link IpPrincipalResolver}) returns an IP-based principal; a later account-aware resolver
 * (reading an auth token / API key, falling back to IP for anonymous callers) is a drop-in with no
 * change to the rate-limit filter or quota interceptor that consume it.
 */
public interface PrincipalResolver {

    RequestPrincipal resolve(HttpServletRequest request);
}
