package com.vacancytracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.vacancytracker.support.AbstractIntegrationTest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class PackageApiTest extends AbstractIntegrationTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    @Autowired
    private MockMvc mvc;

    private String vacancy;

    @BeforeEach
    void seed() throws Exception {
        vacancy = id(mvc.perform(post("/api/vacancies").contentType(JSON).content(
                "{\"title\":\"Backend Engineer\",\"company\":\"Acme Pay\",\"url\":\"https://jobs.example.com/acme/1\",\"description\":\"Java.\"}"))
                .andReturn().getResponse().getContentAsString());
    }

    private static String id(String json) {
        return JsonPath.read(json, "$.id");
    }

    private String document(String target, String kind, String content) throws Exception {
        return id(mvc.perform(post("/api/vacancies/" + target + "/documents").contentType(JSON)
                        .content("{\"kind\":\"" + kind + "\",\"content\":\"" + content + "\",\"origin\":\"manual\"}"))
                .andReturn().getResponse().getContentAsString());
    }

    private static Map<String, String> unzip(byte[] bytes) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                files.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    @Test
    void aPackageHoldsTheChosenVersionsAndSubmittingMovesTheVacancyToApplied() throws Exception {
        document(vacancy, "resume", "# Jane v1");
        String resume = document(vacancy, "resume", "# Jane v2");
        String letter = document(vacancy, "cover_letter", "Dear Acme Pay team");

        String prepared = mvc.perform(post("/api/vacancies/" + vacancy + "/packages").contentType(JSON)
                        .content("{\"resumeDocumentId\":\"" + resume + "\",\"coverLetterDocumentId\":\"" + letter + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("prepared"))
                .andExpect(jsonPath("$.applyUrl").value("https://jobs.example.com/acme/1"))
                .andReturn().getResponse().getContentAsString();
        String pkg = id(prepared);

        Map<String, String> files = unzip(mvc.perform(get("/api/packages/" + pkg + "/download"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(files.keySet()).containsExactlyInAnyOrder("resume-v2-Acme-Pay.pdf", "resume-v2-Acme-Pay.docx", "resume-v2-Acme-Pay.md",
                "cover-letter-v1-Acme-Pay.txt", "cover-letter-v1-Acme-Pay.docx", "vacancy.txt", "checklist.md");
        assertThat(files.get("resume-v2-Acme-Pay.md")).isEqualTo("# Jane v2");
        assertThat(files.get("checklist.md")).contains("https://jobs.example.com/acme/1").contains("submit it yourself")
                .contains("Upload the resume: resume-v2-Acme-Pay.pdf");
        mvc.perform(get("/api/vacancies/" + vacancy)).andExpect(jsonPath("$.status").value("New"));

        mvc.perform(post("/api/packages/" + pkg + "/submitted").contentType(JSON).content("{\"note\":\"via the company site\"}"))
                .andExpect(jsonPath("$.vacancyStatus").value("Applied"))
                .andExpect(jsonPath("$.applicationPackage.status").value("submitted"));
        String history = mvc.perform(get("/api/vacancies/" + vacancy + "/history")).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(history, "$[1].to")).isEqualTo("Applied");
        assertThat(JsonPath.<String>read(history, "$[1].note"))
                .isEqualTo("applied with package " + pkg.substring(0, 8) + ", resume v2, cover letter v1: via the company site");

        mvc.perform(post("/api/packages/" + pkg + "/submitted")).andExpect(jsonPath("$.vacancyStatus").value("Applied"));
        mvc.perform(get("/api/vacancies/" + vacancy + "/history")).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/vacancies/" + vacancy + "/packages")).andExpect(jsonPath("$[0].submittedAt").isNotEmpty());
    }

    @Test
    void withoutATailoredResumeThePackageCarriesTheUploadedFile() throws Exception {
        mvc.perform(post("/api/resume/file").param("fileName", "jane.txt").contentType("text/plain")
                .content("Jane Doe, Java".getBytes(StandardCharsets.UTF_8))).andExpect(status().isOk());
        String pkg = id(mvc.perform(post("/api/vacancies/" + vacancy + "/packages").contentType(JSON)
                        .content("{\"applyUrl\":\"https://acme.example/apply\"}"))
                .andExpect(jsonPath("$.applyUrl").value("https://acme.example/apply"))
                .andReturn().getResponse().getContentAsString());

        Map<String, String> files = unzip(mvc.perform(get("/api/packages/" + pkg + "/download"))
                .andReturn().getResponse().getContentAsByteArray());

        assertThat(files).containsEntry("resume-original-jane.txt", "Jane Doe, Java");
        assertThat(files).containsKey("resume.pdf");
        assertThat(files.get("checklist.md")).contains("Upload the resume: resume.pdf");
    }

    @Test
    void replacingTheResumeLaterDoesNotChangeAPreparedPackage() throws Exception {
        mvc.perform(post("/api/resume/file").param("fileName", "jane-2025.txt").contentType("text/plain")
                .content("Jane 2025".getBytes(StandardCharsets.UTF_8))).andExpect(status().isOk());
        String pkg = id(mvc.perform(post("/api/vacancies/" + vacancy + "/packages").contentType(JSON).content("{}"))
                .andExpect(jsonPath("$.resumeFileName").value("jane-2025.txt"))
                .andReturn().getResponse().getContentAsString());

        mvc.perform(post("/api/resume/file").param("fileName", "jane-2026.txt").contentType("text/plain")
                .content("Jane 2026".getBytes(StandardCharsets.UTF_8))).andExpect(status().isOk());

        Map<String, String> files = unzip(mvc.perform(get("/api/packages/" + pkg + "/download"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(files).containsEntry("resume-original-jane-2025.txt", "Jane 2025").doesNotContainKey("resume-original-jane-2026.txt");
    }

    @Test
    void aPastedResumeGoesIntoThePackageAsTextAndWord() throws Exception {
        mvc.perform(post("/api/resume/text").contentType(JSON).content("{\"text\":\"Jane Doe, Java and Kafka\"}"))
                .andExpect(status().isOk());
        String pkg = id(mvc.perform(post("/api/vacancies/" + vacancy + "/packages").contentType(JSON).content("{}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        Map<String, String> files = unzip(mvc.perform(get("/api/packages/" + pkg + "/download"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());

        assertThat(files).containsEntry("resume.txt", "Jane Doe, Java and Kafka").containsKey("resume.docx").containsKey("resume.pdf");
        assertThat(files.get("checklist.md")).contains("Upload the resume: resume.pdf");
    }

    @Test
    void foreignVersionsBadLinksAndAMissingResumeAreRefused() throws Exception {
        mvc.perform(post("/api/vacancies/" + vacancy + "/packages").contentType(JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Choose a resume version, or upload your resume first."));

        String other = id(mvc.perform(post("/api/vacancies").contentType(JSON).content("{\"title\":\"Other\",\"company\":\"Beta\"}"))
                .andReturn().getResponse().getContentAsString());
        String foreign = document(other, "resume", "Not this one");
        mvc.perform(post("/api/vacancies/" + vacancy + "/packages").contentType(JSON).content("{\"resumeDocumentId\":\"" + foreign + "\"}"))
                .andExpect(status().isUnprocessableEntity());
        String mine = document(vacancy, "resume", "Mine");
        mvc.perform(post("/api/vacancies/" + vacancy + "/packages").contentType(JSON)
                        .content("{\"resumeDocumentId\":\"" + mine + "\",\"applyUrl\":\"javascript:alert(1)\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/vacancies/" + vacancy + "/packages").contentType(JSON).content("{\"coverLetterDocumentId\":\"" + mine + "\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/packages/" + java.util.UUID.randomUUID() + "/download")).andExpect(status().isNotFound());
    }
}
