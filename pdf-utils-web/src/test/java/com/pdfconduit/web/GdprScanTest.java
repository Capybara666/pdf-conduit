package com.pdfconduit.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end REST test for {@code POST /api/gdpr-scan}: a PDF carrying an email + a valid IBAN
 * yields a HIGH-risk JSON report with findings; a clean PDF yields zero findings / NONE risk.
 */
@SpringBootTest
class GdprScanTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private static MockMultipartFile pdf(byte[] bytes) {
        return new MockMultipartFile("file", "doc.pdf", "application/pdf", bytes);
    }

    @Test
    void scan_pdfWithPersonalData_reportsFindingsAndHighRisk() throws Exception {
        // Email → CONTACT, valid mod-97 IBAN → FINANCIAL (which forces HIGH risk).
        byte[] pdf = TestPdfs.withText("Contact john.doe@example.com IBAN DE89370400440532013000");

        mvc().perform(multipart("/api/gdpr-scan").file(pdf(pdf)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.risk").value("HIGH"))
            .andExpect(jsonPath("$.pagesScanned").value(1))
            .andExpect(jsonPath("$.totalFindings").value(2))
            .andExpect(jsonPath("$.countsByCategory.CONTACT").value(1))
            .andExpect(jsonPath("$.countsByCategory.FINANCIAL").value(1))
            .andExpect(jsonPath("$.findings[?(@.type=='EMAIL')]").exists())
            .andExpect(jsonPath("$.findings[?(@.type=='IBAN')]").exists());
    }

    @Test
    void scan_valueFinding_includesRedactRegions() throws Exception {
        // A single email → one finding whose regions can be fed straight into the redact tool
        // (pageIndex + x/y/width/height in displayed-page points, top-left origin, 0-based page).
        byte[] pdf = TestPdfs.withText("Contact john.doe@example.com");

        mvc().perform(multipart("/api/gdpr-scan").file(pdf(pdf)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.findings[0].type").value("EMAIL"))
            .andExpect(jsonPath("$.findings[0].regions").isArray())
            .andExpect(jsonPath("$.findings[0].regions[0].pageIndex").value(0))
            .andExpect(jsonPath("$.findings[0].regions[0].width")
                .value(org.hamcrest.Matchers.greaterThan(5.0)))
            .andExpect(jsonPath("$.findings[0].regions[0].height")
                .value(org.hamcrest.Matchers.greaterThan(3.0)));
    }

    @Test
    void scan_cleanPdf_reportsNoFindings() throws Exception {
        byte[] pdf = TestPdfs.withText("Nothing sensitive here, just a friendly note.");

        mvc().perform(multipart("/api/gdpr-scan").file(pdf(pdf)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.risk").value("NONE"))
            .andExpect(jsonPath("$.totalFindings").value(0))
            .andExpect(jsonPath("$.findings").isEmpty());
    }

    @Test
    void scan_maskedSample_neverLeaksTheRawValue() throws Exception {
        byte[] pdf = TestPdfs.withText("IBAN DE89370400440532013000");

        mvc().perform(multipart("/api/gdpr-scan").file(pdf(pdf)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.findings[0].maskedSample").exists())
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("DE89370400440532013000"))));
    }
}
