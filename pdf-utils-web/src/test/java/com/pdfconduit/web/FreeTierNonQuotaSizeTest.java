package com.pdfconduit.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The production-shaped version of the free-tier size cap, for the endpoints that do NOT count
 * against the daily quota. With the cap at its shipped 25 MB value, a 30 MB upload to
 * {@code /api/render} or {@code /api/metadata/read} must be rejected with 413 {@code too_large}
 * exactly like {@code /api/compress} — before it can occupy one of the four heavy load-guard slots
 * for up to the processing timeout, at zero quota cost. Normal small requests still succeed.
 *
 * <p>The caps must therefore run for every uploading endpoint, not only the
 * {@code Endpoints.isQuotaOp} ones — otherwise these accept anything up to the raw multipart
 * ceiling ({@code max-file-size: 100MB}).
 */
@SpringBootTest(properties = {
    "pdfconduit.web.quota.enabled=true",
    "pdfconduit.web.quota.free-max-file-size=25MB",
    "pdfconduit.web.quota.daily-operations=1000",
    "pdfconduit.web.ratelimit.enabled=false"
})
@AutoConfigureMockMvc
class FreeTierNonQuotaSizeTest {

    /** Comfortably over the 25 MB free-tier cap, comfortably under the 100 MB multipart ceiling. */
    private static final byte[] THIRTY_MB = new byte[30 * 1024 * 1024];

    @Autowired
    private MockMvc mvc;

    private static MockMultipartFile part(String field, String name, byte[] bytes) {
        return new MockMultipartFile(field, name, "application/pdf", bytes);
    }

    @Test
    void render_thirtyMb_returns413() throws Exception {
        mvc.perform(multipart("/api/render").file(part("file", "big.pdf", THIRTY_MB))
                .param("page", "0"))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code").value("too_large"));
    }

    @Test
    void metadataRead_thirtyMb_returns413() throws Exception {
        mvc.perform(multipart("/api/metadata/read").file(part("file", "big.pdf", THIRTY_MB)))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code").value("too_large"));
    }

    @Test
    void formFields_thirtyMb_returns413() throws Exception {
        mvc.perform(multipart("/api/form-fields").file(part("file", "big.pdf", THIRTY_MB)))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code").value("too_large"));
    }

    /** The reference behaviour these three must now match. */
    @Test
    void compress_thirtyMb_returns413() throws Exception {
        mvc.perform(multipart("/api/compress").file(part("files", "big.pdf", THIRTY_MB))
                .param("targetSize", "1MB"))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code").value("too_large"));
    }

    @Test
    void render_normalFile_stillSucceeds() throws Exception {
        mvc.perform(multipart("/api/render").file(part("file", "a.pdf", TestPdfs.blank(1)))
                .param("page", "0")
                .param("dpi", "72"))
            .andExpect(status().isOk());
    }

    @Test
    void metadataRead_normalFile_stillSucceeds() throws Exception {
        mvc.perform(multipart("/api/metadata/read").file(part("file", "a.pdf", TestPdfs.blank(1))))
            .andExpect(status().isOk());
    }

    @Test
    void formFields_normalFile_stillSucceeds() throws Exception {
        mvc.perform(multipart("/api/form-fields").file(part("file", "a.pdf", TestPdfs.blank(1))))
            .andExpect(status().isOk());
    }

    /**
     * The size cap now runs for these endpoints, but the DAILY COUNT must not: a read-only
     * analysis stays quota-free, so it emits no {@code X-Quota-*} headers and consumes nothing.
     */
    @Test
    void readOnlyAnalyses_doNotConsumeQuota() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(multipart("/api/metadata/read").file(part("file", "a.pdf", TestPdfs.blank(1))))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-Quota-Limit"))
                .andExpect(header().doesNotExist("X-Quota-Remaining"));
        }
        mvc.perform(multipart("/api/render").file(part("file", "a.pdf", TestPdfs.blank(1)))
                .param("page", "0").param("dpi", "72"))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("X-Quota-Limit"));
    }
}
