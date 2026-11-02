package com.vacancytracker.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.vacancytracker.support.AbstractIntegrationTest;
import java.io.ByteArrayInputStream;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class VacancyXlsxExportTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void xlsxContainsHeaderAndRows() throws Exception {
        String id = JsonPath.read(mvc.perform(post("/api/vacancies").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Data Engineer\",\"company\":\"Lumen\"}")).andReturn().getResponse().getContentAsString(), "$.id");
        assertThat(id).isNotBlank();

        MvcResult started = mvc.perform(get("/api/export/vacancies.xlsx").param("columns", "title,company,salary_min"))
                .andExpect(request().asyncStarted())
                .andReturn();
        byte[] bytes = mvc.perform(asyncDispatch(started)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheet("Vacancies");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("title");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Data Engineer");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("Lumen");
            assertThat(sheet.getLastRowNum()).isEqualTo(1);
        }
    }
}
