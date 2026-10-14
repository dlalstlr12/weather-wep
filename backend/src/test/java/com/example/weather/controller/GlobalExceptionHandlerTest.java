package com.example.weather.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.weather.service.KmaApiException;
import com.example.weather.service.WeatherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 컨트롤러 예외가 상태 코드와 ProblemDetail 본문으로 변환되는지 확인한다 (Phase 0에서는 모두 403 빈 본문이었다). */
class GlobalExceptionHandlerTest {

    private WeatherService weather;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        weather = mock(WeatherService.class);
        mvc = MockMvcBuilders.standaloneSetup(new WeatherController(weather))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void illegalArgumentBecomes400WithMessage() throws Exception {
        when(weather.getCurrent(any(), any(), eq("제주"), anyBoolean())).thenThrow(new IllegalArgumentException("지원하지 않는 도시입니다: 제주"));
        mvc.perform(get("/api/weather/current").param("city", "제주"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("지원하지 않는 도시입니다: 제주"));
    }

    @Test
    void kmaFailureBecomes502WithKind() throws Exception {
        when(weather.getCurrent(any(), any(), any(), anyBoolean()))
                .thenThrow(new KmaApiException(KmaApiException.Kind.RESULT_CODE, "KMA 오류: code=22", "22", null));
        mvc.perform(get("/api/weather/current").param("lat", "37.5").param("lon", "127.0"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.kind").value("RESULT_CODE"))
                .andExpect(jsonPath("$.detail").value("기상청 데이터를 가져오지 못했습니다."));
    }

    @Test
    void unreachableBecomes503() throws Exception {
        when(weather.getCurrent(any(), any(), any(), anyBoolean()))
                .thenThrow(new KmaApiException(KmaApiException.Kind.UNREACHABLE, "timeout", null, null));
        mvc.perform(get("/api/weather/current").param("lat", "37.5").param("lon", "127.0"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.kind").value("UNREACHABLE"));
    }

    @Test
    void openBreakerBecomes503() throws Exception {
        when(weather.getCurrent(any(), any(), any(), anyBoolean()))
                .thenThrow(new KmaApiException(KmaApiException.Kind.CIRCUIT_OPEN, "open", null, null));
        mvc.perform(get("/api/weather/current").param("lat", "37.5").param("lon", "127.0"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.kind").value("CIRCUIT_OPEN"));
    }

    @Test
    void noDataBecomes502() throws Exception {
        when(weather.getCurrent(any(), any(), any(), anyBoolean()))
                .thenThrow(new KmaApiException(KmaApiException.Kind.NO_DATA, "no data", "03", null));
        mvc.perform(get("/api/weather/current").param("lat", "37.5").param("lon", "127.0"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.kind").value("NO_DATA"));
    }

    @Test
    void unexpectedExceptionBecomes500WithoutLeakingInternals() throws Exception {
        when(weather.getCurrent(any(), any(), any(), anyBoolean())).thenThrow(new IllegalStateException("db password=secret"));
        mvc.perform(get("/api/weather/current").param("lat", "37.5").param("lon", "127.0"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("서버 오류가 발생했습니다."));
    }
}
