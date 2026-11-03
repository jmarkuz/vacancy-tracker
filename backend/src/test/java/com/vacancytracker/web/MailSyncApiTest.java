package com.vacancytracker.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vacancytracker.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** The mailbox read through the API when no mailbox is set up (the tests have none). */
@SpringBootTest
@AutoConfigureMockMvc
class MailSyncApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void startingAReadWithoutAMailboxIsRefusedWithTheReason() throws Exception {
        mvc.perform(post("/api/mail/sync/start"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.started").value(false))
                .andExpect(jsonPath("$.reason").value("The mailbox is not set up"));
    }

    @Test
    void theStatusSaysNothingIsRunningAndHasNoResultYet() throws Exception {
        mvc.perform(get("/api/mail/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.syncing").value(false))
                .andExpect(jsonPath("$.lastResult").doesNotExist());
    }
}
