package com.pdfconduit.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The other half of every admission ceiling: real work must still go through. A guard that refuses
 * abuse and ordinary use alike has not made the service safer, it has turned it off — so the
 * workloads a user actually brings are pinned here at the SHIPPED strict defaults (no relaxed
 * profile, no loosened properties), where the estimate is tightest.
 *
 * <p>The last test pins the opposite: the one common workload that is genuinely refused, so the
 * boundary is documented rather than discovered by a user.
 */
@SpringBootTest(properties = {
    "pdfconduit.web.ratelimit.enabled=false",
    "pdfconduit.web.quota.enabled=false"
})
@AutoConfigureMockMvc
class LegitimateWorkloadsTest {

    @Autowired
    private MockMvc mvc;

    /** A large-but-ordinary upload (12 MB, comfortably under the 25 MB free-tier per-file cap). */
    @Test
    void largeSingleFileRotate_succeeds() throws Exception {
        mvc.perform(multipart("/api/rotate")
                .file(new MockMultipartFile("files", "scan.pdf", "application/pdf",
                    TestPdfs.bulky(12 * 1024 * 1024)))
                .param("angle", "90"))
            .andExpect(status().isOk());
    }

    /** A batch of ordinary files: the aggregate ceilings must not turn multi-file work off. */
    @Test
    void multiFileBatchWithinBudget_succeeds() throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/rotate");
        for (int i = 0; i < 5; i++) {
            request.file(new MockMultipartFile("files", "f" + i + ".pdf", "application/pdf",
                TestPdfs.bulky(2 * 1024 * 1024)));
        }
        mvc.perform(request.param("angle", "180")).andExpect(status().isOk());
    }

    /** The SPA's default export: pages → PNG at 150 DPI, the most common rasterising request. */
    @Test
    void pageExportAtDefaultDpi_succeeds() throws Exception {
        mvc.perform(multipart("/api/to-images")
                .file(new MockMultipartFile("files", "doc.pdf", "application/pdf", TestPdfs.blank(8)))
                .param("format", "png")
                .param("dpi", "150"))
            .andExpect(status().isOk());
    }

    /** A pipeline a person would actually build in the editor: a few stages over a few files. */
    @Test
    void everydayPipeline_succeeds() throws Exception {
        String json = "{\"nodes\":["
            + "{\"id\":\"s\",\"kind\":\"SOURCE\",\"x\":0,\"y\":0,\"files\":[\"a.pdf\",\"b.pdf\"]},"
            + "{\"id\":\"r\",\"kind\":\"ROTATE\",\"x\":0,\"y\":0,\"pages\":\"\",\"angle\":90},"
            + "{\"id\":\"m\",\"kind\":\"MERGE\",\"x\":0,\"y\":0}],"
            + "\"connections\":[{\"fromNodeId\":\"s\",\"toNodeId\":\"r\"},"
            + "{\"fromNodeId\":\"r\",\"toNodeId\":\"m\"}]}";

        mvc.perform(multipart("/api/pipeline/run")
                .file(new MockMultipartFile("files", "a.pdf", "application/pdf", TestPdfs.blank(3)))
                .file(new MockMultipartFile("files", "b.pdf", "application/pdf", TestPdfs.blank(2)))
                .param("pipeline", json))
            .andExpect(status().isOk());
    }

    /**
     * The documented boundary: a 60-page scan exported at the maximum 300 DPI is ~522 megapixels,
     * over the 500 MP per-request render ceiling, so it is refused — and it is refused before a
     * single page is rasterised, with a message naming what to change. The same document at the
     * SPA's default 150 DPI is a quarter of that and goes through (see above).
     */
    @Test
    void sixtyPageExportAtMaxDpi_isRefusedWithAnActionableMessage() throws Exception {
        mvc.perform(multipart("/api/to-images")
                .file(new MockMultipartFile("files", "scan.pdf", "application/pdf",
                    TestPdfs.blank(60)))
                .param("format", "png")
                .param("dpi", "300"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("output_too_large"))
            .andExpect(jsonPath("$.error", containsString("lower DPI")));
    }
}
