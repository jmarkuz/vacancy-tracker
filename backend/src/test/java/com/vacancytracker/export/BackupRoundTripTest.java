package com.vacancytracker.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.vacancytracker.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class BackupRoundTripTest extends AbstractIntegrationTest {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void backupRestoresVacanciesWithHistoryAndUserFields() throws Exception {
        String id = JsonPath.read(mvc.perform(post("/api/vacancies").contentType(JSON)
                .content("{\"title\":\"Backend, Java\",\"company\":\"Northwind\",\"url\":\"https://ex.com/a?utm_source=x\"}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/vacancies/" + id + "/status").contentType(JSON).content("{\"status\":\"Saved\",\"note\":\"good\"}"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/vacancies/" + id)
                .contentType(JSON).content("{\"notes\":\"call back\",\"tags\":[\"dream\"]}"));

        String backup = exportBackup();
        jdbc.execute("TRUNCATE vacancy CASCADE");

        ImportReport report = importJson(backup);

        assertThat(report.imported()).isEqualTo(1);
        assertThat(report.errors()).isEmpty();
        mvc.perform(get("/api/vacancies/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Saved"))
                .andExpect(jsonPath("$.notes").value("call back"))
                .andExpect(jsonPath("$.tags[0]").value("dream"))
                .andExpect(jsonPath("$.canonicalUrl").value("https://ex.com/a"));
        mvc.perform(get("/api/vacancies/" + id + "/history"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].note").value("good"));
    }

    @Test
    void reimportingAnEditedCsvKeepsDescriptionHistoryAndEditMarks() throws Exception {
        String id = JsonPath.read(mvc.perform(post("/api/vacancies").contentType(JSON)
                .content("{\"title\":\"Role\",\"company\":\"Northwind\",\"description\":\"Long text\"}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/vacancies/" + id + "/status").contentType(JSON).content("{\"status\":\"Saved\"}"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/vacancies/" + id)
                .contentType(JSON).content("{\"description\":\"My edit\"}"));

        ImportReport report = importCsvText("id,title,company,status,priority\r\n" + id + ",Role,Northwind,Applied,2\r\n");

        assertThat(report.imported()).isEqualTo(1);
        mvc.perform(get("/api/vacancies/" + id))
                .andExpect(jsonPath("$.status").value("Applied"))
                .andExpect(jsonPath("$.priority").value(2))
                .andExpect(jsonPath("$.description").value("My edit"));
        mvc.perform(get("/api/vacancies/" + id + "/history"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].note").value("imported from CSV"));
    }

    @Test
    void aCsvWithFewColumnsLeavesTheOtherFieldsOfAnExistingVacancyAlone() throws Exception {
        String id = JsonPath.read(mvc.perform(post("/api/vacancies").contentType(JSON)
                .content("{\"title\":\"Role\",\"company\":\"Northwind\",\"url\":\"https://ex.com/r\"}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/vacancies/" + id + "/status").contentType(JSON).content("{\"status\":\"Saved\"}"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/vacancies/" + id)
                .contentType(JSON).content("{\"notes\":\"keep\",\"tags\":[\"dream\"],\"priority\":1}"));

        importCsvText("id,title,company\r\n" + id + ",Role renamed,Northwind\r\n");

        mvc.perform(get("/api/vacancies/" + id))
                .andExpect(jsonPath("$.title").value("Role renamed"))
                .andExpect(jsonPath("$.status").value("Saved"))
                .andExpect(jsonPath("$.notes").value("keep"))
                .andExpect(jsonPath("$.tags[0]").value("dream"))
                .andExpect(jsonPath("$.priority").value(1))
                .andExpect(jsonPath("$.url").value("https://ex.com/r"));
        mvc.perform(get("/api/vacancies/" + id + "/history")).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void textThatLooksLikeAFormulaIsNeutralizedInCsvAndComesBackUnchanged() throws Exception {
        String id = JsonPath.read(mvc.perform(post("/api/vacancies").contentType(JSON)
                .content("{\"title\":\"=HYPERLINK(\\\"http://x\\\")\",\"company\":\"'-Acme\"}"))
                .andReturn().getResponse().getContentAsString(), "$.id");

        MvcResult started = mvc.perform(get("/api/export/vacancies.csv").param("columns", "id,title,company"))
                .andExpect(request().asyncStarted()).andReturn();
        String csv = mvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(csv).contains("'=HYPERLINK").contains("''-Acme");
        jdbc.execute("TRUNCATE vacancy CASCADE");
        assertThat(importCsvText(csv).imported()).isEqualTo(1);
        mvc.perform(get("/api/vacancies/" + id))
                .andExpect(jsonPath("$.title").value("=HYPERLINK(\"http://x\")"))
                .andExpect(jsonPath("$.company").value("'-Acme"));
    }

    @Test
    void invalidRowsAreReportedAndValidRowsStored() throws Exception {
        String csv = "title,company,status,priority\r\n"
                + "Good role,Acme,Saved,2\r\n"
                + "Bad status,Acme,Bogus,\r\n"
                + ",Acme,New,\r\n";

        ImportReport report = importCsvText(csv);

        assertThat(report.total()).isEqualTo(3);
        assertThat(report.imported()).isEqualTo(1);
        assertThat(report.errors()).extracting(ImportReport.RowError::row, ImportReport.RowError::field)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(2, "status"), org.assertj.core.groups.Tuple.tuple(3, "title"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vacancy", Integer.class)).isEqualTo(1);
    }

    @Test
    void unsupportedBackupVersionIsUnprocessable() throws Exception {
        mvc.perform(post("/api/import/vacancies").contentType(JSON).content("{\"schemaVersion\":99,\"vacancies\":[]}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void unknownCsvColumnIsUnprocessable() throws Exception {
        mvc.perform(post("/api/import/vacancies").contentType("text/csv").content("title,nope\r\nx,y\r\n"))
                .andExpect(status().isUnprocessableEntity());
    }

    private String exportBackup() throws Exception {
        MvcResult started = mvc.perform(get("/api/export/backup.json")).andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private ImportReport importJson(String body) throws Exception {
        return read(mvc.perform(post("/api/import/vacancies").contentType(JSON).content(body)));
    }

    private ImportReport importCsvText(String body) throws Exception {
        return read(mvc.perform(post("/api/import/vacancies").contentType("text/csv").content(body)));
    }

    private ImportReport read(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        String json = actions.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().readValue(json, ImportReport.class);
    }
}
