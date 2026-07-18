package com.pdfconduit.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The raster-render DPI guard (S1): an absurd {@code dpi} on the page → image endpoints must be
 * rejected up front (400 {@code bad_request}) rather than allocating hundreds of gigabytes and
 * OOM-ing the JVM. The default cap is 300 DPI; a normal small DPI still succeeds.
 */
@SpringBootTest(properties = {
    "pdfconduit.web.ratelimit.enabled=false",
    "pdfconduit.web.quota.enabled=false"
})
@AutoConfigureMockMvc
class RenderLimitsTest {

    @Autowired
    private MockMvc mvc;

    private static MockMultipartFile file(String field, byte[] bytes) {
        return new MockMultipartFile(field, "a.pdf", "application/pdf", bytes);
    }

    @Test
    void render_hugeDpi_rejectedNotOom() throws Exception {
        byte[] a = TestPdfs.blank(1);
        mvc.perform(multipart("/api/render").file(file("file", a))
                .param("page", "0")
                .param("dpi", "60000"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("bad_request"));
    }

    @Test
    void toImages_hugeDpi_rejectedNotOom() throws Exception {
        byte[] a = TestPdfs.blank(1);
        mvc.perform(multipart("/api/to-images").file(file("file", a))
                .param("dpi", "60000"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("bad_request"));
    }

    @Test
    void render_reasonableDpi_succeeds() throws Exception {
        byte[] a = TestPdfs.blank(1);
        mvc.perform(multipart("/api/render").file(file("file", a))
                .param("page", "0")
                .param("dpi", "72"))
            .andExpect(status().isOk());
    }
}
