package com.schemaguard.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driverClassName=org.h2.Driver",
        "spring.sql.init.mode=always"
})
@AutoConfigureMockMvc
class Phase8ApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldExposeRulesDefaultProjectAndExecuteAnalysisViaRestEndpoints() throws Exception {
        // 1. GET /api/v1/rules -> returns all 12 rules (SG001-SG012)
        mockMvc.perform(get("/api/v1/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(12)))
                .andExpect(jsonPath("$[0].ruleId", is("SG001")));

        // 2. GET /api/v1/projects/proj-sample-company -> returns seeded project & runs
        mockMvc.perform(get("/api/v1/projects/proj-sample-company"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.project.id", is("proj-sample-company")))
                .andExpect(jsonPath("$.recentRuns", hasSize(greaterThanOrEqualTo(2))));

        // 3. POST /api/v1/projects/proj-sample-company/analyses -> triggers live analysis
        String payload = """
                {
                  "baseCommit": "main@111",
                  "headCommit": "feat/drop-email@222",
                  "baselineSql": "CREATE TABLE public.customers (id UUID PRIMARY KEY, email TEXT NOT NULL);",
                  "candidateSql": "ALTER TABLE public.customers DROP COLUMN email;",
                  "candidateFileName": "V12__remove_email.sql",
                  "strictMode": true
                }
                """;

        mockMvc.perform(post("/api/v1/projects/proj-sample-company/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.decision", is("BLOCK")))
                .andExpect(jsonPath("$.exitCode", is(1)))
                .andExpect(jsonPath("$.findings[*].ruleId", hasItems("SG001", "SG008")));
    }
}