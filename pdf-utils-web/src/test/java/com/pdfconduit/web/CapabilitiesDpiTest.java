package com.pdfconduit.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/capabilities} advertises {@code maxDpi}, the render ceiling.
 *
 * <p>Why it must be advertised: a DPI above the ceiling is <em>rejected</em> (400
 * {@code bad_request}), never clamped. The SPA's "PDF → Images" form and the pipeline inspector let
 * the user pick a DPI, so any locally hard-coded maximum that is higher than the server's calls a
 * value valid and then fails on submit — the client-side guard has to be the server's own number.
 *
 * <p>Like {@link CapabilitiesLimitsTest}, this restates no limit: it READS what the endpoint
 * advertises and probes the enforcing path at exactly that boundary (advertised DPI → 200, one over
 * → 400). Move the ceiling on either side alone and the boundary moves, so this fails.
 */
@SpringBootTest(properties = {
    // A ceiling deliberately different from the shipped 300, so a hard-coded 300 anywhere in the
    // advertise-or-enforce path is caught rather than accidentally agreeing.
    "pdfconduit.web.render.max-dpi=137",
    "pdfconduit.web.ratelimit.enabled=false",
    "pdfconduit.web.quota.enabled=false"
})
@AutoConfigureMockMvc
class CapabilitiesDpiTest {

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper json = new ObjectMapper();

    private JsonNode capabilities() throws Exception {
        String body = mvc.perform(get("/api/capabilities"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private static MockMultipartFile pdf(String field, byte[] bytes) {
        return new MockMultipartFile(field, "a.pdf", "application/pdf", bytes);
    }

    @Test
    void advertisesMaxDpi_alongsideTheExistingFields() throws Exception {
        mvc.perform(get("/api/capabilities"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.maxDpi").value(137))
            // The pre-existing fields must survive the extension.
            .andExpect(jsonPath("$.maxFileSizeBytes").exists())
            .andExpect(jsonPath("$.maxFilesPerRequest").exists())
            .andExpect(jsonPath("$.officeEnabled").exists())
            .andExpect(jsonPath("$.ocrLanguages").isArray());
    }

    /** to-images: exactly the advertised DPI renders; one more is refused with the existing code. */
    @Test
    void toImages_isEnforcedAtExactlyTheAdvertisedDpi() throws Exception {
        int advertised = capabilities().get("maxDpi").asInt();
        assertEquals(137, advertised);
        byte[] onePage = TestPdfs.blank(1);

        mvc.perform(multipart("/api/to-images").file(pdf("files", onePage))
                .param("dpi", String.valueOf(advertised)))
            .andExpect(status().isOk());

        mvc.perform(multipart("/api/to-images").file(pdf("files", onePage))
                .param("dpi", String.valueOf(advertised + 1)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("bad_request"))
            // The rejection must quote the SAME number we advertised.
            .andExpect(jsonPath("$.error",
                org.hamcrest.Matchers.containsString("maximum allowed (" + advertised + ")")));
    }

    /** The single-page render endpoint shares the ceiling, so it shares the boundary. */
    @Test
    void render_isEnforcedAtExactlyTheAdvertisedDpi() throws Exception {
        int advertised = capabilities().get("maxDpi").asInt();
        byte[] onePage = TestPdfs.blank(1);

        mvc.perform(multipart("/api/render").file(pdf("file", onePage))
                .param("page", "0").param("dpi", String.valueOf(advertised)))
            .andExpect(status().isOk());

        mvc.perform(multipart("/api/render").file(pdf("file", onePage))
                .param("page", "0").param("dpi", String.valueOf(advertised + 1)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("bad_request"));
    }
}
