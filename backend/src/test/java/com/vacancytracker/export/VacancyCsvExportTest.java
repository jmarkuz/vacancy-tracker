package com.vacancytracker.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vacancytracker.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class VacancyCsvExportTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void csvHasBomHeaderQuotingAndStatusFilter() throws Exception {
        create("Engineer, Platform \"core\"", "Zürich Labs", "Saved");
        create("Plain role", "Acme", null);

        MvcResult started = mvc.perform(get("/api/export/vacancies.csv").param("status", "Saved")
                        .param("columns", "title,company,status"))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult done = mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn();
        String body = done.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).startsWith("﻿title,company,status\r\n");
        assertThat(body).contains("\"Engineer, Platform \"\"core\"\"\",Zürich Labs,Saved\r\n");
        assertThat(body).doesNotContain("Plain role");
    }

    @Test
    void unknownColumnIsUnprocessable() throws Exception {
        mvc.perform(get("/api/export/vacancies.csv").param("columns", "nope"))
                .andExpect(status().isUnprocessableEntity());
    }

    private void create(String title, String company, String statusLabel) throws Exception {
        String id = com.jayway.jsonpath.JsonPath.read(mvc.perform(post("/api/vacancies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title.replace("\"", "\\\"") + "\",\"company\":\"" + company + "\"}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        if (statusLabel != null) {
            mvc.perform(post("/api/vacancies/" + id + "/status").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"status\":\"" + statusLabel + "\"}"));
        }
    }
}
