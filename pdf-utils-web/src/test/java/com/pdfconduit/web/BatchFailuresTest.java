package com.pdfconduit.web;

import com.pdfconduit.core.operations.PdfProtector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Partial-tolerant MAP batches: one unusable file must not cost the user the whole upload.
 *
 * <p>The case that matters: fifteen PDFs compressed, number fourteen is password-protected. The
 * other fourteen results must still come back, and the response must NAME the bad file — a bare
 * 422 "The PDF is password-protected." leaves re-uploading subsets as the only way to find it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchFailuresTest {

    @Autowired
    private MockMvc mvc;

    private static MockMultipartFile pdf(String name, byte[] bytes) {
        return new MockMultipartFile("files", name, "application/pdf", bytes);
    }

    /** A PDF nobody can open without the password — the classic mid-batch spoiler. */
    private static byte[] encrypted() throws Exception {
        return PdfProtector.executeBytes(TestPdfs.blank(1), "secret", null);
    }

    private static List<String> zipEntryNames(byte[] zip) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) names.add(e.getName());
        }
        return names;
    }

    @Test
    void compress_oneBadFile_returnsTheGoodOnesAndNamesTheBadOne() throws Exception {
        MvcResult result = mvc.perform(multipart("/api/compress")
                .file(pdf("good.pdf", TestPdfs.withText("hello")))
                .file(pdf("encrypted.pdf", encrypted()))
                .param("targetSize", "5MB"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/zip"))
            .andExpect(header().string("X-Batch-Failures",
                org.hamcrest.Matchers.startsWith("encrypted.pdf: ")))
            .andExpect(header().string("X-Batch-Failures",
                org.hamcrest.Matchers.containsString("password-protected")))
            .andReturn();

        // The archive carries exactly the file that survived, under its own name.
        assertThat(zipEntryNames(result.getResponse().getContentAsByteArray()))
            .containsExactly("good_compressed.pdf");
    }

    @Test
    void rotate_oneBadFileAmongThree_keepsTheOtherTwo() throws Exception {
        MvcResult result = mvc.perform(multipart("/api/rotate")
                .file(pdf("a.pdf", TestPdfs.blank(1)))
                .file(pdf("locked.pdf", encrypted()))
                .file(pdf("b.pdf", TestPdfs.blank(2)))
                .param("angle", "90"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Batch-Failures",
                org.hamcrest.Matchers.startsWith("locked.pdf: ")))
            .andReturn();

        assertThat(zipEntryNames(result.getResponse().getContentAsByteArray()))
            .containsExactly("a_rotated.pdf", "b_rotated.pdf");
    }

    @Test
    void batch_withNoFailures_sendsNoFailureHeader() throws Exception {
        mvc.perform(multipart("/api/rotate")
                .file(pdf("a.pdf", TestPdfs.blank(1)))
                .file(pdf("b.pdf", TestPdfs.blank(1)))
                .param("angle", "90"))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("X-Batch-Failures"));
    }

    @Test
    void batch_whereEveryFileFails_stillFails_withTheFirstFileNamed() throws Exception {
        mvc.perform(multipart("/api/rotate")
                .file(pdf("locked1.pdf", encrypted()))
                .file(pdf("locked2.pdf", encrypted()))
                .param("angle", "90"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("operation_failed"))
            .andExpect(jsonPath("$.error", org.hamcrest.Matchers.startsWith("locked1.pdf: ")));
    }

    @Test
    void singleBadFile_stillFails_butNowNamesTheFile() throws Exception {
        mvc.perform(multipart("/api/rotate")
                .file(pdf("only.pdf", encrypted()))
                .param("angle", "90"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("operation_failed"))
            .andExpect(jsonPath("$.error", org.hamcrest.Matchers.startsWith("only.pdf: ")));
    }

    /** REDUCE is not partial-tolerant: a merge missing an input is a different document. */
    @Test
    void merge_withOneBadFile_failsWholesale_butNamesTheFile() throws Exception {
        mvc.perform(multipart("/api/merge")
                .file(pdf("good.pdf", TestPdfs.blank(1)))
                .file(pdf("broken.pdf", "this is definitely not a pdf".getBytes(StandardCharsets.UTF_8))))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("operation_failed"))
            .andExpect(jsonPath("$.error", org.hamcrest.Matchers.startsWith("broken.pdf: ")));
    }
}
