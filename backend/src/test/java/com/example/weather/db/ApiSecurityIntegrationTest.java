package com.example.weather.db;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.weather.dto.WeatherDtos;
import com.example.weather.service.WeatherService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 실제 보안 설정(JWT 필터), 인증·날씨·챗 컨트롤러, PostgreSQL을 함께 띄워 접근 규칙을 확인한다.
 * 기상청은 목, LLM은 키를 비워 꺼 둔다(규칙 기반 답변으로 응답).
 */
@AutoConfigureMockMvc
@SpringBootTest(properties = "anthropic.api-key=")
class ApiSecurityIntegrationTest extends PostgisIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @MockBean
    WeatherService weather;

    private static final String CHAT_BODY = "{\"messages\":[{\"role\":\"user\",\"content\":\"서울 지금 날씨\"}]}";

    private String signupAndLogin() throws Exception {
        String email = "u-" + UUID.randomUUID() + "@example.com";
        String signup = "{\"name\":\"테스트\",\"email\":\"" + email + "\",\"password\":\"pw-1234\"}";
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signup)).andExpect(status().isOk());
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"pw-1234\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("accessToken").asText();
    }

    @Test
    void chatRequiresLogin() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(CHAT_BODY)).andExpect(status().isForbidden());
        mvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON).content(CHAT_BODY)).andExpect(status().isForbidden());
    }

    @Test
    void invalidTokenIsRejected() throws Exception {
        mvc.perform(post("/api/chat").header("Authorization", "Bearer not-a-real-token")
                        .contentType(MediaType.APPLICATION_JSON).content(CHAT_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void loggedInUserGetsAChatAnswerEvenWithoutLlm() throws Exception {
        when(weather.getCurrent(any(), any(), any(), anyBoolean())).thenReturn(new WeatherDtos.CurrentWeatherResponse(18.5, 0.0, "맑음"));
        String token = signupAndLogin();

        String answer = mvc.perform(post("/api/chat").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(CHAT_BODY))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertTrue(answer.contains("18.5"), answer);
    }

    @Test
    void chatRejectsARequestWithoutMessages() throws Exception {
        String token = signupAndLogin();
        mvc.perform(post("/api/chat").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void weatherIsPublic() throws Exception {
        when(weather.getCurrent(any(), any(), any(), anyBoolean())).thenReturn(new WeatherDtos.CurrentWeatherResponse(1.5, 0.0, "흐림"));
        mvc.perform(get("/api/weather/current").param("city", "서울"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.temperature").value(1.5));
    }

    @Test
    void duplicateSignupAndWrongPasswordAreBadRequests() throws Exception {
        String email = "dup-" + UUID.randomUUID() + "@example.com";
        String body = "{\"name\":\"a\",\"email\":\"" + email + "\",\"password\":\"pw-1234\"}";
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("이미 가입된 이메일입니다."));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"wrong\"}"))
                .andExpect(status().isBadRequest());
    }
}
