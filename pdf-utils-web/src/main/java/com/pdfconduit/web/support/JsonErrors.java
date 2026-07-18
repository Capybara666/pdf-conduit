package com.pdfconduit.web.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes the API's {@code {code,error,...}} JSON body straight onto a servlet response. Used by
 * the rate-limit filter and quota interceptor, which short-circuit a request before it reaches a
 * controller (so {@code @RestControllerAdvice} never sees it). Field order is preserved.
 */
public final class JsonErrors {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonErrors() {}

    /** Writes {@code {code,error}} with the given HTTP status. */
    public static void write(HttpServletResponse resp, int status, String code, String error)
            throws IOException {
        write(resp, status, code, error, null);
    }

    /** Writes {@code {code,error, ...extra}} with the given HTTP status. */
    public static void write(HttpServletResponse resp, int status, String code, String error,
                             Map<String, ?> extra) throws IOException {
        if (resp.isCommitted()) return;
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("error", error);
        if (extra != null) body.putAll(extra);
        MAPPER.writeValue(resp.getWriter(), body);
    }
}
