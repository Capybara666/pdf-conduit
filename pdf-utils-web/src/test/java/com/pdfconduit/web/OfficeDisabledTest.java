package com.pdfconduit.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * When {@code pdfconduit.web.office.enabled=false}, office/document uploads must be rejected up
 * front (415) without ever invoking LibreOffice — so this test needs no {@code soffice} install.
 */
@SpringBootTest(properties = "pdfconduit.web.office.enabled=false")
class OfficeDisabledTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void officeUpload_whenDisabled_returns415() throws Exception {
        // Bytes are never converted — the .docx extension alone triggers the rejection.
        MockMultipartFile docx = new MockMultipartFile("files", "report.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "not really a docx".getBytes());
        mvc().perform(multipart("/api/to-pdf").file(docx))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.code").value("office_disabled"));
    }
}
