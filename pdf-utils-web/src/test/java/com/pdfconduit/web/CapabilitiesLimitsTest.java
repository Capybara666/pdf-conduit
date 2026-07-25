package com.pdfconduit.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The SPA sizes its pre-upload guard from {@code GET /api/capabilities}, so an advertised number
 * that is not the enforced one is worse than no number at all: it either refuses work the service
 * would have done, or lets the user wait out a doomed upload for a 413.
 *
 * <p>This test never restates the limits — it READS what the endpoint advertises and then probes the
 * enforcing path at exactly that boundary: {@code n} files accepted / {@code n + 1} rejected,
 * {@code b} bytes accepted / {@code b + 1} rejected. Change a limit on either side alone and the
 * boundary moves, so this fails.
 *
 * <p>The per-file cap is pinned (via {@link DynamicPropertySource}) to the exact byte length of a
 * real one-page PDF, which is what makes the "accepted at exactly the cap" half of the probe a real
 * 200 rather than a "not 413".
 */
@SpringBootTest(properties = {
    "pdfconduit.web.quota.enabled=true",
    "pdfconduit.web.quota.free-max-files=2",
    "pdfconduit.web.ratelimit.enabled=false"
})
@AutoConfigureMockMvc
class CapabilitiesLimitsTest {

    /** A real PDF whose exact length becomes the configured per-file cap. */
    private static final byte[] PDF = blankPdf();

    private static byte[] blankPdf() {
        try {
            return TestPdfs.blank(1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void pinFileSizeCapToTheProbeFile(DynamicPropertyRegistry registry) {
        registry.add("pdfconduit.web.quota.free-max-file-size", () -> PDF.length + "B");
    }

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper json = new ObjectMapper();

    private JsonNode capabilities() throws Exception {
        String body = mvc.perform(get("/api/capabilities"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private static MockMultipartFile part(String field, String name, byte[] bytes) {
        return new MockMultipartFile(field, name, "application/pdf", bytes);
    }

    /** A byte array of exactly {@code size} bytes: the real PDF, padded with trailing whitespace. */
    private static byte[] sized(int size) {
        byte[] out = Arrays.copyOf(PDF, size);
        Arrays.fill(out, PDF.length, size, (byte) '\n');
        return out;
    }

    @Test
    void advertisesTheCaps_atAll() throws Exception {
        mvc.perform(get("/api/capabilities"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.maxFileSizeBytes").value(PDF.length))
            .andExpect(jsonPath("$.maxFilesPerRequest").value(2))
            // The pre-existing fields must survive the extension.
            .andExpect(jsonPath("$.officeEnabled").exists())
            .andExpect(jsonPath("$.ocrLanguages").isArray());
    }

    /** The advertised file count is the exact count the interceptor stops accepting at. */
    @Test
    void fileCount_isEnforcedAtExactlyTheAdvertisedNumber() throws Exception {
        int advertised = capabilities().get("maxFilesPerRequest").asInt();
        assertTrue(advertised >= 2, "probe needs at least 2 files, advertised " + advertised);

        // Exactly the advertised count: accepted (a real merge, not merely "not rejected").
        MockMultipartHttpServletRequestBuilder ok = multipart("/api/merge");
        for (int i = 0; i < advertised; i++) ok.file(part("files", "f" + i + ".pdf", PDF));
        mvc.perform(ok).andExpect(status().isOk());

        // One more: rejected before any work happens.
        MockMultipartHttpServletRequestBuilder tooMany = multipart("/api/merge");
        for (int i = 0; i <= advertised; i++) tooMany.file(part("files", "f" + i + ".pdf", PDF));
        mvc.perform(tooMany)
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code").value("too_large"))
            // The rejection message must quote the SAME number we advertised.
            .andExpect(jsonPath("$.error").value(
                org.hamcrest.Matchers.containsString("at most " + advertised + " files")));
    }

    /** The advertised per-file byte cap is the exact size the interceptor stops accepting at. */
    @Test
    void fileSize_isEnforcedAtExactlyTheAdvertisedNumber() throws Exception {
        long advertised = capabilities().get("maxFileSizeBytes").asLong();
        assertEquals(PDF.length, advertised, "cap pinned to the probe PDF's exact length");

        // Exactly at the cap: accepted.
        mvc.perform(multipart("/api/rotate")
                .file(part("files", "a.pdf", sized((int) advertised)))
                .param("angle", "90"))
            .andExpect(status().isOk());

        // One byte over: rejected, and never parsed.
        mvc.perform(multipart("/api/rotate")
                .file(part("files", "a.pdf", sized((int) advertised + 1)))
                .param("angle", "90"))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code").value("too_large"));
    }
}
