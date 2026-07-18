package com.pdfconduit.web.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Assigns every request a correlation id and makes it observable end-to-end:
 * <ul>
 *   <li>honours an inbound {@code X-Request-Id} (sanitised + length-capped to defeat log
 *       injection) or mints a fresh UUID;</li>
 *   <li>puts it in the SLF4J {@link MDC} under {@code requestId} so the structured log pattern
 *       (see {@code logback-spring.xml}) tags every line of this request's work;</li>
 *   <li>echoes it back as the {@code X-Request-Id} response header so a client/proxy can
 *       correlate a response with server logs.</li>
 * </ul>
 * Runs at the very outside of the filter chain (highest precedence) so even a rate-limit or
 * quota rejection is logged and returned with its id. Only the id is touched — never bodies or PII.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    private static final int MAX_LEN = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String id = sanitize(request.getHeader(HEADER));
        if (id == null) id = UUID.randomUUID().toString();
        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * Accepts an inbound id only if it is a short token of safe characters (letters, digits,
     * {@code . _ -}); anything else (including control chars that could forge log lines) is
     * rejected so we fall back to a generated UUID.
     */
    private static String sanitize(String raw) {
        if (raw == null) return null;
        String trimmed = raw.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LEN) return null;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            if (!ok) return null;
        }
        return trimmed;
    }
}
