package com.vacancytracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.vacancytracker.application.documents.ResumePdf;
import com.vacancytracker.application.documents.ResumePdfTest;
import com.vacancytracker.support.AbstractIntegrationTest;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The resume PDF of at most two pages (T-097), with the fake AI only. */
@SpringBootTest(properties = "app.ai.provider=fake")
@AutoConfigureMockMvc
class ResumePdfApiTest extends AbstractIntegrationTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    private String preset;
    private String vacancy;

    @BeforeEach
    void seed() throws Exception {
        preset = JsonPath.read(mvc.perform(get("/api/presets")).andReturn().getResponse().getContentAsString(), "$[0].id");
        mvc.perform(patch("/api/presets/" + preset).contentType(JSON).content("{\"mainGoal\":\"Backend roles.\"}"));
        vacancy = JsonPath.read(mvc.perform(post("/api/vacancies").contentType(JSON)
                        .content("{\"title\":\"Backend Engineer\",\"company\":\"Acme Pay\",\"description\":\"We use Java, Kafka, Kubernetes.\"}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static String json(String text) throws Exception {
        return MAPPER.writeValueAsString(text);
    }

    private void baseResume(String text) throws Exception {
        mvc.perform(post("/api/presets/" + preset + "/base-resume/text").contentType(JSON).content("{\"text\":" + json(text) + "}"))
                .andExpect(status().isOk());
    }

    private static int pages(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return doc.getNumberOfPages();
        }
    }

    @Test
    void everySavedResumeVersionHasAPdfOfAtMostTwoPagesAndThePackageCarriesIt() throws Exception {
        String saved = mvc.perform(post("/api/vacancies/" + vacancy + "/documents").contentType(JSON)
                        .content("{\"kind\":\"resume\",\"content\":" + json(ResumePdfTest.RESUME) + ",\"origin\":\"manual\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pdfPages").value(1))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(saved, "$.id");

        byte[] pdf = mvc.perform(get("/api/documents/" + id + "/download").param("format", "pdf"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(new PDFTextStripper().getText(doc)).contains("Олена Коваль").contains("EXPERIENCE").contains("Cut payment latency by 40%");
        }

        String pkg = JsonPath.read(mvc.perform(post("/api/vacancies/" + vacancy + "/packages").contentType(JSON)
                .content("{\"resumeDocumentId\":\"" + id + "\"}")).andReturn().getResponse().getContentAsString(), "$.id");
        Map<String, byte[]> files = unzip(mvc.perform(get("/api/packages/" + pkg + "/download")).andReturn().getResponse().getContentAsByteArray());
        assertThat(pages(files.get("resume-v1-Acme-Pay.pdf"))).isBetween(1, 2);
    }

    @Test
    void aTooLongResumeIsNeverSavedNorPackagedAndAiShortensItAtMostTwice() throws Exception {
        String tooLong = ResumePdfTest.longResume(300);
        mvc.perform(post("/api/vacancies/" + vacancy + "/documents").contentType(JSON)
                        .content("{\"kind\":\"resume\",\"content\":" + json(tooLong) + ",\"origin\":\"manual\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("at most 2 are allowed")));
        mvc.perform(get("/api/vacancies/" + vacancy + "/documents")).andExpect(jsonPath("$.length()").value(0));

        // Used as it is, a base resume that long is refused too, and it cannot go into a package.
        baseResume(tooLong);
        mvc.perform(post("/api/vacancies/" + vacancy + "/documents/as-is").contentType(JSON).content("{\"kind\":\"resume\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/vacancies/" + vacancy + "/packages").contentType(JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("pages")));

        mvc.perform(post("/api/ai/consent"));
        // Two shortenings are not enough for six pages: the owner is asked to edit by hand.
        mvc.perform(post("/api/vacancies/" + vacancy + "/documents/shorten").contentType(JSON).content("{\"text\":" + json(tooLong) + "}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("after 2 shortening attempts")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("by hand")));
        // A little over two pages: one shortening is enough.
        String slightlyLong = ResumePdfTest.longResume(130);
        assertThat(ResumePdf.render(slightlyLong).pages()).isEqualTo(3);
        mvc.perform(post("/api/vacancies/" + vacancy + "/documents/shorten").contentType(JSON).content("{\"text\":" + json(slightlyLong) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pages").value(2))
                .andExpect(jsonPath("$.attempts").value(1));
    }

    @Test
    void aGeneratedResumeOverTwoPagesIsShortenedBeforeTheOwnerSeesIt() throws Exception {
        baseResume(ResumePdfTest.longResume(130));
        mvc.perform(post("/api/ai/consent"));

        mvc.perform(post("/api/vacancies/" + vacancy + "/documents/draft").contentType(JSON).content("{\"kind\":\"resume\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pages").value(2))
                .andExpect(jsonPath("$.shortened").value(1))
                .andExpect(jsonPath("$.pageProblem").doesNotExist());

        baseResume(ResumePdfTest.longResume(300));
        mvc.perform(post("/api/vacancies/" + vacancy + "/documents/draft").contentType(JSON).content("{\"kind\":\"resume\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortened").value(2))
                .andExpect(jsonPath("$.pageProblem").value(org.hamcrest.Matchers.containsString("by hand")));
    }

    @Test
    void anUploadedPdfOfOneOrTwoPagesIsUsedAsItIsWithAiOff() throws Exception {
        byte[] original = ResumePdf.render(ResumePdfTest.RESUME).bytes();
        mvc.perform(post("/api/presets/" + preset + "/base-resume/file").param("fileName", "olena.pdf").contentType(MediaType.APPLICATION_PDF)
                .content(original)).andExpect(status().isOk());

        String saved = mvc.perform(post("/api/vacancies/" + vacancy + "/documents/as-is").contentType(JSON).content("{\"kind\":\"resume\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.origin").value("base"))
                .andExpect(jsonPath("$.pdfPages").value(1))
                .andReturn().getResponse().getContentAsString();
        byte[] pdf = mvc.perform(get("/api/documents/" + JsonPath.read(saved, "$.id") + "/download").param("format", "pdf"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(pdf).isEqualTo(original);

        // The uploaded PDF goes into a package without a tailored version unchanged as well.
        String pkg = JsonPath.read(mvc.perform(post("/api/vacancies/" + vacancy + "/packages").contentType(JSON).content("{}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        Map<String, byte[]> files = unzip(mvc.perform(get("/api/packages/" + pkg + "/download")).andReturn().getResponse().getContentAsByteArray());
        assertThat(files.get("resume-original-olena.pdf")).isEqualTo(original);
        assertThat(files).doesNotContainKey("resume.pdf");
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                files.put(e.getName(), zip.readAllBytes());
            }
        }
        return files;
    }
}
