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
 * The free-tier per-file size cap: with the cap set to 100 bytes, any real PDF upload exceeds it
 * and is rejected with 413 {@code too_large} bearing a free-tier message — stricter than the
 * absolute multipart ceiling.
 */
@SpringBootTest(properties = {
    "pdfconduit.web.quota.enabled=true",
    "pdfconduit.web.quota.free-max-file-size=100B",
    "pdfconduit.web.ratelimit.enabled=false"
})
@AutoConfigureMockMvc
class FreeTierSizeTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void fileOverFreeCap_returns413() throws Exception {
        byte[] a = TestPdfs.blank(1); // a real PDF is comfortably larger than 100 bytes
        MockMultipartFile file = new MockMultipartFile("files", "a.pdf", "application/pdf", a);

        mvc.perform(multipart("/api/rotate").file(file).param("angle", "90"))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code").value("too_large"))
            .andExpect(jsonPath("$.error", containsString("free-tier")));
    }
}
