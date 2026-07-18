package com.pdfconduit.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Produces the OpenAPI contract as a build artifact and guards that it is populated.
 *
 * <p>Springdoc serves the live spec at {@code GET /v3/api-docs}. This test boots the app, fetches
 * that spec, writes it to {@code target/openapi.json} (the input for the frontend type-codegen,
 * R2 WU-B), and asserts the spec is non-trivial: the drift-prone DTO schema names must be present,
 * so a controller/DTO that stops being documented fails the build rather than silently dropping a
 * type from the generated client.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDumpTest {

    /** Springdoc's default JSON endpoint; not under {@code /api}, so no rate-limit/quota gating. */
    private static final String API_DOCS = "/v3/api-docs";

    /**
     * Schema names that MUST appear in the spec — drift here breaks downstream FE codegen.
     *
     * <p>Note: the error shape {@code ApiError} is deliberately NOT in this list. Springdoc only
     * emits schemas reachable from a documented response, and {@code ApiError} is produced solely
     * by the {@code @RestControllerAdvice} exception handler, which springdoc does not scan without
     * an explicit {@code @ApiResponse} on the controllers. Documenting it requires a source change
     * (a follow-up); until then the error body is a known gap in the generated client.
     */
    private static final String[] REQUIRED_SCHEMAS = {
        "OperationInfo", "MetadataDto", "PiiReportDto",
        "NodeKindInfo", "ValidationErrorDto",
    };

    @Autowired
    private MockMvc mvc;

    @Test
    void dumpsOpenApiSpec_andSpecIsPopulated() throws Exception {
        MvcResult result = mvc.perform(get(API_DOCS))
            .andExpect(status().isOk())
            .andReturn();

        String spec = result.getResponse().getContentAsString();
        assertThat(spec).as("empty OpenAPI response body").isNotBlank();

        // Persist the contract for the frontend type generator (R2 WU-B).
        Path out = Path.of("target", "openapi.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, spec, StandardCharsets.UTF_8);

        // A valid OpenAPI 3 document.
        assertThat(spec).contains("\"openapi\"");

        // The spec must actually document our DTOs, not just be a shell with paths.
        for (String schema : REQUIRED_SCHEMAS) {
            assertThat(spec)
                .as("OpenAPI spec is missing schema '%s' — that endpoint/DTO is not documented", schema)
                .contains("\"" + schema + "\"");
        }
    }
}
