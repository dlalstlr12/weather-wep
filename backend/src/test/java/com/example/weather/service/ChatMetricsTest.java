package com.example.weather.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 챗이 LLM 경로로 갔는지, 왜 규칙 기반으로 내려갔는지, 토큰과 도구 호출이 얼마나 쓰였는지 측정값으로 남기는지 확인한다. */
class ChatMetricsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TEXT = """
            {"content":[{"type":"text","text":"맑아요."}],"stop_reason":"end_turn","usage":{"input_tokens":120,"output_tokens":20}}""";
    private static final String TOOL = """
            {"content":[{"type":"tool_use","id":"t1","name":"get_current_weather","input":{"location":"서울"}},
                        {"type":"tool_use","id":"t2","name":"made_up_tool","input":{}}],
             "stop_reason":"tool_use","usage":{"input_tokens":300,"output_tokens":40}}""";

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private AnthropicClient llm;
    private WeatherTools tools;
    private WeatherService weather;

    @BeforeEach
    void setUp() {
        llm = mock(AnthropicClient.class);
        tools = mock(WeatherTools.class);
        weather = mock(WeatherService.class);
        when(llm.enabled()).thenReturn(true);
        when(tools.definitions()).thenReturn(MAPPER.createArrayNode());
    }

    private ChatService service(int perUser) {
        var props = new AnthropicProperties("k", "http://unused", "m", 400, 1000, 1000, 3, 6, 500, perUser, 100);
        return new ChatService(llm, props, tools, new ChatQuota(props), weather, MAPPER,
                Clock.system(ZoneId.of("Asia/Seoul")), registry);
    }

    private static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ChatDtos.ChatRequest ask(String q) {
        return new ChatDtos.ChatRequest(List.of(new ChatDtos.Message("user", q)), null, null, null);
    }

    private double requests(String path, String reason) {
        var c = registry.find("chat.requests").tag("path", path).tag("reason", reason).counter();
        return c == null ? 0 : c.count();
    }

    private double tokens(String type) {
        var c = registry.find("chat.llm.tokens").tag("type", type).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void successfulLlmAnswerCountsRequestTokensToolCallsAndDuration() {
        when(llm.create(anyString(), any(), any())).thenReturn(json(TOOL)).thenReturn(json(TEXT));
        when(tools.execute(anyString(), any(), any())).thenReturn(new WeatherTools.Result("{}", false));

        service(10).chat(ask("서울 날씨"), "u1");

        assertEquals(1, requests("llm", "ok"));
        assertEquals(420, tokens("input"));   // 300 + 120
        assertEquals(60, tokens("output"));   // 40 + 20
        assertEquals(1, registry.find("chat.tool.calls").tag("tool", "get_current_weather").tag("error", "false").counter().count());
        // 모델이 지어낸 도구 이름은 그대로 태그가 되지 않는다
        assertEquals(1, registry.find("chat.tool.calls").tag("tool", "unknown").counter().count());
        assertNull(registry.find("chat.tool.calls").tag("tool", "made_up_tool").counter());
        assertEquals(1, registry.find("chat.llm.duration").tag("outcome", "ok").timer().count());
    }

    @Test
    void fallbackReasonsAreDistinguished() {
        when(weather.getCurrent(any(), any(), anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new WeatherDtos.CurrentWeatherResponse(10.0, 0.0, "맑음"));

        // 키 없음
        when(llm.enabled()).thenReturn(false);
        service(10).chat(ask("서울 지금 날씨"), "u1");
        assertEquals(1, requests("fallback", "disabled"));

        // 호출 실패
        when(llm.enabled()).thenReturn(true);
        doThrow(new LlmException("boom")).when(llm).create(anyString(), any(), any());
        service(10).chat(ask("서울 지금 날씨"), "u1");
        assertEquals(1, requests("fallback", "error"));

        // 호출 횟수 초과
        doAnswer(inv -> json(TOOL)).when(llm).create(anyString(), any(), any());
        when(tools.execute(anyString(), any(), any())).thenReturn(new WeatherTools.Result("{}", false));
        service(10).chat(ask("서울 지금 날씨"), "u1");
        assertEquals(1, requests("fallback", "call_limit"));

        assertEquals(0, requests("llm", "ok"));
    }

    @Test
    void countersExistAtZeroBeforeTheFirstEventSoPrometheusIncreaseSeesTheFirstOne() {
        service(10);
        assertEquals(0, registry.find("chat.requests").tag("path", "fallback").tag("reason", "quota").counter().count());
        assertEquals(0, registry.find("chat.llm.tokens").tag("type", "input").counter().count());
        assertEquals(0, registry.find("chat.tool.calls").tag("tool", "get_forecast").tag("error", "true").counter().count());
    }

    @Test
    void quotaExceededIsCountedAsItsOwnReason() {
        when(llm.create(anyString(), any(), any())).thenReturn(json(TEXT));
        when(weather.getCurrent(any(), any(), anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new WeatherDtos.CurrentWeatherResponse(10.0, 0.0, "맑음"));
        ChatService s = service(1);

        s.chat(ask("서울 지금 날씨"), "u1");
        s.chat(ask("서울 지금 날씨"), "u1");

        assertEquals(1, requests("llm", "ok"));
        assertEquals(1, requests("fallback", "quota"));
    }
}
