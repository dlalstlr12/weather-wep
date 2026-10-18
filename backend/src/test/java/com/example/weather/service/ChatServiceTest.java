package com.example.weather.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.weather.chat.AnthropicClient;
import com.example.weather.chat.AnthropicProperties;
import com.example.weather.chat.ChatQuota;
import com.example.weather.chat.LlmException;
import com.example.weather.chat.WeatherTools;
import com.example.weather.dto.ChatDtos;
import com.example.weather.dto.WeatherDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ChatService: LLM 도구 호출 루프와, LLM을 쓸 수 없을 때의 규칙 기반 대체 경로.
 * Anthropic은 목으로 대체해 실제 호출(과금)이 없다.
 */
class ChatServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AnthropicClient llm;
    private WeatherService weather;
    private WeatherTools tools;
    private AnthropicProperties props;
    private ChatService chat;

    @BeforeEach
    void setUp() {
        llm = mock(AnthropicClient.class);
        weather = mock(WeatherService.class);
        tools = mock(WeatherTools.class);
        props = props(10, 100);
        when(llm.enabled()).thenReturn(true);
        when(tools.definitions()).thenReturn(MAPPER.createArrayNode());
        chat = new ChatService(llm, props, tools, new ChatQuota(props), weather, MAPPER);
    }

    private static AnthropicProperties props(int perUser, int total) {
        return new AnthropicProperties("test-key", "http://unused", "m", 400, 1000, 1000, 3, 6, 500, perUser, total);
    }

    private static ChatDtos.ChatRequest ask(String text) {
        return new ChatDtos.ChatRequest(List.of(new ChatDtos.Message("user", text)), null, null, null);
    }

    private static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String TEXT_REPLY = """
            {"content":[{"type":"text","text":"서울은 지금 18.5도예요."}],"stop_reason":"end_turn","usage":{"input_tokens":120,"output_tokens":20}}""";
    private static final String TOOL_REPLY = """
            {"content":[{"type":"text","text":"조회할게요"},{"type":"tool_use","id":"tu_1","name":"get_current_weather","input":{"location":"서울"}}],
             "stop_reason":"tool_use","usage":{"input_tokens":300,"output_tokens":40}}""";

    // ---- LLM 경로 ----

    @Test
    void toolUseIsExecutedAndItsResultSentBackToTheModel() {
        List<String> secondCallMessages = new ArrayList<>();
        when(llm.create(anyString(), any(), any())).thenReturn(json(TOOL_REPLY)).thenAnswer(inv -> {
            secondCallMessages.add(inv.<JsonNode>getArgument(2).toString());
            return json(TEXT_REPLY);
        });
        when(tools.execute(eq("get_current_weather"), any(), any()))
                .thenReturn(new WeatherTools.Result("{\"temp_c\":18.5}", false));

        String answer = chat.chat(ask("서울 지금 날씨 어때?"), "u1").content();

        assertEquals("서울은 지금 18.5도예요.", answer);
        verify(llm, times(2)).create(anyString(), any(), any());
        String sent = secondCallMessages.get(0);
        assertTrue(sent.contains("\"tool_use_id\":\"tu_1\""), sent);
        assertTrue(sent.contains("18.5"), sent);
        assertFalse(sent.contains("is_error"), sent);
        verifyNoInteractions(weather); // 규칙 기반 경로는 타지 않는다
    }

    @Test
    void toolErrorIsReportedToTheModelWithIsError() {
        List<String> secondCallMessages = new ArrayList<>();
        when(llm.create(anyString(), any(), any())).thenReturn(json(TOOL_REPLY)).thenAnswer(inv -> {
            secondCallMessages.add(inv.<JsonNode>getArgument(2).toString());
            return json(TEXT_REPLY);
        });
        when(tools.execute(anyString(), any(), any())).thenReturn(new WeatherTools.Result("찾을 수 없는 지역입니다", true));

        chat.chat(ask("없는동 날씨"), "u1");

        assertTrue(secondCallMessages.get(0).contains("\"is_error\":true"));
    }

    @Test
    void modelCallCountIsCappedThenFallsBackToRuleBasedAnswer() {
        when(llm.create(anyString(), any(), any())).thenAnswer(inv -> json(TOOL_REPLY));
        when(tools.execute(anyString(), any(), any())).thenReturn(new WeatherTools.Result("{}", false));
        when(weather.getCurrent(any(), any(), eq("서울"), eq(false)))
                .thenReturn(new WeatherDtos.CurrentWeatherResponse(10.0, 0.0, "맑음"));

        String answer = chat.chat(ask("서울 지금 날씨"), "u1").content();

        verify(llm, times(3)).create(anyString(), any(), any());
        assertTrue(answer.contains("10.0°C"), answer);
    }

    @Test
    void llmFailureFallsBackToRuleBasedAnswer() {
        when(llm.create(anyString(), any(), any())).thenThrow(new LlmException("boom"));
        when(weather.getCurrent(any(), any(), eq("서울"), eq(false)))
                .thenReturn(new WeatherDtos.CurrentWeatherResponse(10.0, 0.0, "맑음"));

        assertTrue(chat.chat(ask("서울 지금 날씨"), "u1").content().contains("10.0°C"));
    }

    @Test
    void disabledLlmNeverCallsTheApi() {
        when(llm.enabled()).thenReturn(false);
        when(weather.getCurrent(any(), any(), eq("서울"), eq(false)))
                .thenReturn(new WeatherDtos.CurrentWeatherResponse(10.0, 0.0, "맑음"));

        chat.chat(ask("서울 지금 날씨"), "u1");

        verify(llm, never()).create(anyString(), any(), any());
    }

    @Test
    void quotaExceededSkipsTheModelButStillAnswers() {
        props = props(1, 100);
        chat = new ChatService(llm, props, tools, new ChatQuota(props), weather, MAPPER);
        when(llm.create(anyString(), any(), any())).thenReturn(json(TEXT_REPLY));
        when(weather.getCurrent(any(), any(), eq("서울"), eq(false)))
                .thenReturn(new WeatherDtos.CurrentWeatherResponse(10.0, 0.0, "맑음"));

        assertEquals("서울은 지금 18.5도예요.", chat.chat(ask("서울 지금 날씨"), "u1").content());
        assertTrue(chat.chat(ask("서울 지금 날씨"), "u1").content().contains("10.0°C")); // 한도 초과 → 규칙 기반
        verify(llm, times(1)).create(anyString(), any(), any());
    }

    @Test
    void historyIsLimitedTruncatedAndStartsWithUser() {
        List<String> sent = new ArrayList<>();
        when(llm.create(anyString(), any(), any())).thenAnswer(inv -> {
            sent.add(inv.<JsonNode>getArgument(2).toString());
            return json(TEXT_REPLY);
        });
        List<ChatDtos.Message> msgs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            msgs.add(new ChatDtos.Message("user", "q" + i));
            msgs.add(new ChatDtos.Message("assistant", "a" + i));
        }
        msgs.add(new ChatDtos.Message("user", "x".repeat(2000)));

        chat.chat(new ChatDtos.ChatRequest(msgs, null, null, null), "u1");

        JsonNode m = json(sent.get(0));
        assertTrue(m.size() <= 6);
        assertEquals("user", m.get(0).path("role").asText());
        assertEquals(500, m.get(m.size() - 1).path("content").asText().length());
    }

    // ---- 규칙 기반 대체 경로 ----

    private ChatDtos.ChatResponse offline(String text) {
        when(llm.enabled()).thenReturn(false);
        return chat.chat(ask(text), "u1");
    }

    @Test
    void weatherQuestionWithoutLocationAsksForIt() {
        assertTrue(offline("지금 날씨 어때?").content().startsWith("도움을 드리려면"));
        verifyNoInteractions(weather);
    }

    @Test
    void forecastForKnownCityAnswersFromData() {
        var fr = new WeatherDtos.ForecastResponse(List.of(
                new WeatherDtos.ForecastEntry("202610141200", 20.0, 0.0, "맑음", 10, 50, 1.0)));
        when(weather.getForecast(any(), any(), eq("부산"), eq(false))).thenReturn(fr);
        assertTrue(offline("부산 예보 알려줘").content().contains("부산"));
    }

    @Test
    void currentAndForecastKeywordsTogetherPreferCurrent() {
        when(weather.getCurrent(any(), any(), eq("서울"), eq(false)))
                .thenReturn(new WeatherDtos.CurrentWeatherResponse(10.0, 0.0, "맑음"));
        assertTrue(offline("서울 오늘 예보").content().contains("10.0°C"));
    }

    @Test
    void staleWeatherIsAnnouncedWithItsAge() {
        when(weather.getCurrent(any(), any(), eq("서울"), eq(false)))
                .thenReturn(new WeatherDtos.CurrentWeatherResponse(10.0, 0.0, "맑음", true, 300));
        String out = offline("서울 지금 날씨").content();
        assertTrue(out.contains("10.0°C"));
        assertTrue(out.contains("5분 전 데이터"));
    }

    @Test
    void weatherServiceFailureGivesApologyNotException() {
        when(weather.getCurrent(any(), any(), anyString(), anyBoolean())).thenThrow(new RuntimeException("KMA down"));
        assertTrue(offline("서울 지금 날씨").content().contains("불러오지 못했어요"));
    }

    @Test
    void nonWeatherQuestionGetsScopeMessage() {
        assertTrue(offline("안녕").content().contains("날씨 질문에만"));
    }
}
