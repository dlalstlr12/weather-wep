package com.example.weather.controller;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HealthControllerTest {

    private MockMvc mvc(JdbcClient jdbc) {
        return MockMvcBuilders.standaloneSetup(new HealthController(jdbc, 300)).build();
    }

    @Test
    void databaseUpGives200() throws Exception {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        when(jdbc.sql("select 1").query(Integer.class).single()).thenReturn(1);

        mvc(jdbc).perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void databaseDownGives503WithReasonButNoErrorDetails() throws Exception {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        when(jdbc.sql("select 1").query(Integer.class).single())
                .thenThrow(new DataAccessResourceFailureException("connection refused: jdbc:postgresql://secret-host:5432/db"));

        mvc(jdbc).perform(get("/api/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.reason").value("database"))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void slowDatabaseGives503WithinTheTimeoutInsteadOfWaitingForTheConnectionPool() throws Exception {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        when(jdbc.sql("select 1").query(Integer.class).single()).thenAnswer(inv -> {
            Thread.sleep(5000); // 커넥션 풀이 연결을 기다리는 상황
            return 1;
        });

        long t0 = System.nanoTime();
        mvc(jdbc).perform(get("/api/health")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.reason").value("database"));
        long ms = (System.nanoTime() - t0) / 1_000_000;

        org.junit.jupiter.api.Assertions.assertTrue(ms < 2000, "제한 시간(300ms)을 넘겨 오래 기다렸다: " + ms + "ms");
    }
}
