package com.pdfconduit.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The PDF-bomb page-count guard: with the ceiling set to 2 pages, a 5-page upload is rejected with
 * 422 {@code operation_failed}. Rate limiting and quota are disabled so only the page guard fires.
 */
@SpringBootTest(properties = {
    "pdfconduit.web.pdf.max-pages=2",
    "pdfconduit.web.ratelimit.enabled=false",
    "pdfconduit.web.quota.enabled=false"
})
@AutoConfigureMockMvc
class MaxPagesTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void pdfOverPageCap_returns422() throws Exception {
        byte[] a = TestPdfs.blank(5);
        MockMultipartFile file = new MockMultipartFile("files", "a.pdf", "application/pdf", a);

        mvc.perform(multipart("/api/extract").file(file))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("operation_failed"))
            .andExpect(jsonPath("$.error", containsString("maximum page count")));
    }
}
