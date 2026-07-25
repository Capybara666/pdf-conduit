package com.pdfconduit.web;

import com.pdfconduit.web.support.Endpoints;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests for {@code POST /api/repair} — real Spring context, real core repair, damaged
 * PDFs corrupted in memory (no binary fixtures). The two contract headers are asserted for both a
 * damaged and an already-healthy upload, because "we rewrote it" and "we fixed it" must never be
 * reported as the same thing.
 */
@SpringBootTest
class RepairControllerTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private static MockMultipartFile pdf(String name, byte[] bytes) {
        return new MockMultipartFile("files", name, "application/pdf", bytes);
    }

    /** Points {@code startxref} past the end of the file: the objects survive, the map to them lies. */
    private static byte[] brokenStartxref(byte[] pdf) {
        byte[] copy = pdf.clone();
        int sx = lastIndexOf(copy, "startxref");
        assertThat(sx).as("fixture: startxref present").isGreaterThan(0);
        int i = sx + "startxref".length();
        while (i < copy.length && (copy[i] == ' ' || copy[i] == '\r' || copy[i] == '\n')) i++;
        int digits = 0;
        while (i + digits < copy.length && copy[i + digits] >= '0' && copy[i + digits] <= '9') digits++;
        assertThat(digits).as("fixture: startxref offset present").isGreaterThan(0);
        Arrays.fill(copy, i, i + digits, (byte) '9');
        return copy;
    }

    private static int lastIndexOf(byte[] data, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = data.length - n.length; i >= 0; i--) {
            for (int j = 0; j < n.length; j++) {
                if (data[i + j] != n[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static int zipEntryCount(byte[] zip) throws java.io.IOException {
        int n = 0;
        try (java.util.zip.ZipInputStream in =
                 new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            while (in.getNextEntry() != null) n++;
        }
        return n;
    }

    // --- single file ------------------------------------------------------

    @Test
    void damagedPdf_isRepaired_andReportedAsRecovered() throws Exception {
        byte[] broken = brokenStartxref(TestPdfs.blank(3));

        MvcResult result = mvc().perform(multipart("/api/repair").file(pdf("broken.pdf", broken)))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString("broken_repaired.pdf")))
            .andExpect(header().string("X-Repair-Was-Damaged", "true"))
            .andExpect(header().string("X-Repair-Recovered", "true"))
            .andExpect(header().string("X-Repair-Pages", "3"))
            .andReturn();

        byte[] out = result.getResponse().getContentAsByteArray();
        assertThat(TestPdfs.pageCount(out)).isEqualTo(3);
        assertThat(result.getResponse().getHeader("X-Repair-Findings"))
            .contains("startxref-invalid").contains("xref-rebuilt");
        assertThat(Long.parseLong(result.getResponse().getHeader("X-Original-Bytes")))
            .isEqualTo(broken.length);
        assertThat(Long.parseLong(result.getResponse().getHeader("X-Result-Bytes")))
            .isEqualTo(out.length);
    }

    @Test
    void healthyPdf_isRewritten_butNotClaimedAsRecovered() throws Exception {
        MvcResult result = mvc().perform(multipart("/api/repair")
                .file(pdf("fine.pdf", TestPdfs.withText("nothing wrong here"))))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string("X-Repair-Was-Damaged", "false"))
            .andExpect(header().string("X-Repair-Recovered", "false"))
            .andExpect(header().string("X-Repair-Findings", ""))
            .andReturn();

        assertThat(TestPdfs.pageCount(result.getResponse().getContentAsByteArray())).isEqualTo(1);
    }

    // --- batch ------------------------------------------------------------

    @Test
    void multipleFiles_returnZip() throws Exception {
        MvcResult result = mvc().perform(multipart("/api/repair")
                .file(pdf("broken.pdf", brokenStartxref(TestPdfs.blank(2))))
                .file(pdf("fine.pdf", TestPdfs.blank(1))))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/zip"))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString("repair_results.zip")))
            .andReturn();

        assertThat(zipEntryCount(result.getResponse().getContentAsByteArray())).isEqualTo(2);
    }

    // --- errors -----------------------------------------------------------

    @Test
    void unrecoverableFile_returns422_withRepairFailedCode() throws Exception {
        byte[] junk = "this is definitely not a pdf".getBytes(StandardCharsets.UTF_8);
        mvc().perform(multipart("/api/repair").file(pdf("hopeless.pdf", junk)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("repair_failed"));
    }

    @Test
    void noFiles_returns400() throws Exception {
        mvc().perform(multipart("/api/repair"))
            .andExpect(status().isBadRequest());
    }

    // --- hardening classification ----------------------------------------

    @Test
    void repairEndpoint_isHeavyAndQuotaCounted() {
        assertThat(Endpoints.isHeavy("/api/repair")).as("must run under the load guard").isTrue();
        assertThat(Endpoints.isQuotaOp("/api/repair")).as("must consume a quota unit").isTrue();
        assertThat(Endpoints.isCheap("/api/repair")).isFalse();
        assertThat(Endpoints.isMetered("/api/repair")).isTrue();
    }
}
