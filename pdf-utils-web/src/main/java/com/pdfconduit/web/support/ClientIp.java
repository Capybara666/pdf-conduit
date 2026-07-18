package com.pdfconduit.web.support;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the client IP used as the rate-limit / quota key. The backend sits behind an edge
 * proxy (nginx), so the real client is the first hop of {@code X-Forwarded-For}; we fall back to
 * the socket peer ({@code getRemoteAddr}) when the header is absent. Only the edge proxy is
 * trusted to set XFF — direct-to-backend deployments should not expose this port publicly.
 */
public final class ClientIp {

    private ClientIp() {}

    public static String resolve(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            String first = (comma >= 0 ? xff.substring(0, comma) : xff).strip();
            if (!first.isBlank()) return first;
        }
        String remote = req.getRemoteAddr();
        return (remote == null || remote.isBlank()) ? "unknown" : remote;
    }
}
