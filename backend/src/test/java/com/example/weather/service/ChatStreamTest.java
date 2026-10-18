package com.example.weather.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.weather.chat.AnthropicClient;
import com.example.weather.chat.AnthropicProperties;
import com.example.weather.chat.ChatQuota;
import com.example.weather.chat.ChatSink;
import com.example.weather.chat.LlmException;
import com.example.weather.chat.StreamClosedException;
import com.example.weather.chat.WeatherTools;
import com.example.weather.dto.ChatDtos;
import com.example.weather.dto.WeatherDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

/** 스트리밍 챗: 도구 상태·답변 조각·reset 이벤트가 맞는 순서로 나가고, 실패하거나 연결이 끊겼을 때 올바르게 끝나는지 확인한다. */
class ChatStreamTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOOL = """
            {"content":[{"type":"tool_use","id":"t1","name":"get_current_weather","input":{"location":"서울"}}],
             "stop_reason":"tool_use","usage":{"input_tokens":300,"output_tokens":40}}""";
    private static final String TEXT = """
            {"content":[{"type":"text","text":"서울은 18.5도예요."}],"stop_reason":"end_turn","usage":{"input_tokens":120,"output_tokens":20}}""";

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private AnthropicClient llm;
    private WeatherTools tools;
    private WeatherService weather;
    private ChatService chat;
    private final List<String> events = new ArrayList<>();

    /** 받은 이벤트를 "종류:내용" 문자열로 기록하는 sink. */
    private final ChatSink sink = new ChatSink() {
        @Override
        public void status(String text) {
            events.add("status:" + text);
        }

        @Override
        public void delta(String text) {
            events.add("delta:" + text);
        }

        @Override
        public void reset() {
            events.add("reset");
        }
    };

    @BeforeEach
    void setUp() {
        llm = mock(AnthropicClient.class);
        tools = mock(WeatherTools.class);
        weather = mock(WeatherService.class);
        when(llm.enabled()).thenReturn(true);
        when(tools.definitions()).thenReturn(MAPPER.createArrayNode());
        when(tools.execute(anyString(), any(), any())).thenReturn(new WeatherTools.Result("{\"temp_c\":18.5}", false));
        var props = new AnthropicProperties("k", "http://unused", "m", 400, 1000, 1000, 4, 6, 500, 10, 100);
        chat = new ChatService(llm, props, tools, new ChatQuota(props), weather, MAPPER, Clock.system(ZoneId.of("Asia/Seoul")), registry);
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

    /** llm.stream 호출에서 핸들러로 이벤트를 흘려 보낸 뒤 응답을 돌려주는 동작. */
    private static org.mockito.stubbing.Answer<JsonNode> streams(String response, String toolStart, String... texts) {
        return (InvocationOnMock inv) -> {
            AnthropicClient.StreamHandler h = inv.getArgument(3);
            for (String t : texts) h.onText(t);
            if (toolStart != null) h.onToolStart(toolStart);
            return json(response);
        };
    }

    @Test
    void toolStatusThenAnswerChunksAreSentInOrder() {
        doAnswer(streams(TOOL, "get_current_weather")).doAnswer(streams(TEXT, null, "서울은 ", "18.5도예요."))
                .when(llm).stream(anyString(), any(), any(), any());

        chat.chatStream(ask("서울 지금 날씨"), "u1", sink);

        assertEquals(List.of("status:현재 날씨를 조회하는 중이에요", "delta:서울은 ", "delta:18.5도예요."), events);
        verify(llm, never()).create(anyString(), any(), any());
        assertEquals(1, registry.find("chat.requests").tag("path", "llm").tag("reason", "ok").counter().count());
        assertEquals(420, registry.find("chat.llm.tokens").tag("type", "input").counter().count());
    }

    @Test
    void textStreamedBeforeAToolCallIsClearedWithReset() {
        doAnswer(streams(TOOL, "get_current_weather", "조회할게요. ")).doAnswer(streams(TEXT, null, "서울은 18.5도예요."))
                .when(llm).stream(anyString(), any(), any(), any());

        chat.chatStream(ask("서울 지금 날씨"), "u1", sink);

        assertEquals(List.of("delta:조회할게요. ", "reset", "status:현재 날씨를 조회하는 중이에요", "delta:서울은 18.5도예요."), events);
    }

    @Test
    void withoutLlmTheWholeRuleBasedAnswerIsSentAsOneChunk() {
        when(llm.enabled()).thenReturn(false);
        when(weather.getCurrent(any(), any(), anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new WeatherDtos.CurrentWeatherResponse(10.0, 0.0, "맑음"));

        chat.chatStream(ask("서울 지금 날씨"), "u1", sink);

        assertEquals(1, events.size());
        assertTrue(events.get(0).startsWith("delta:") && events.get(0).contains("10.0°C"), events.toString());
    }

    @Test
    void failureAfterPartialTextReplacesItWithTheRuleBasedAnswer() {
        when(weather.getCurrent(any(), any(), anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new WeatherDtos.CurrentWeatherResponse(10.0, 0.0, "맑음"));
        doAnswer(inv -> {
            ((AnthropicClient.StreamHandler) inv.getArgument(3)).onText("서울은 지");
            throw new LlmException("스트림이 끊겼습니다");
        }).when(llm).stream(anyString(), any(), any(), any());

        chat.chatStream(ask("서울 지금 날씨"), "u1", sink);

        assertEquals("delta:서울은 지", events.get(0));
        assertEquals("reset", events.get(1));
        assertTrue(events.get(2).contains("10.0°C"), events.toString());
        assertEquals(1, registry.find("chat.requests").tag("path", "fallback").tag("reason", "error").counter().count());
    }

    @Test
    void closedConnectionStopsCallingTheModelAndSendsNoFallback() {
        ChatSink closed = new ChatSink() {
            @Override
            public void status(String text) {
                throw new StreamClosedException(new java.io.IOException("broken pipe"));
            }

            @Override
            public void delta(String text) {
                throw new StreamClosedException(new java.io.IOException("broken pipe"));
            }

            @Override
            public void reset() {
                throw new StreamClosedException(new java.io.IOException("broken pipe"));
            }
        };
        doAnswer(streams(TOOL, "get_current_weather")).when(llm).stream(anyString(), any(), any(), any());

        chat.chatStream(ask("서울 지금 날씨"), "u1", closed);

        verify(llm, times(1)).stream(anyString(), any(), any(), any()); // 두 번째 호출을 하지 않는다
        verify(tools, never()).execute(anyString(), any(), any());
        assertEquals(1, registry.find("chat.requests").tag("path", "llm").tag("reason", "cancelled").counter().count());
        assertEquals(0, registry.find("chat.requests").tag("path", "fallback").tag("reason", "error").counter().count());
    }

    @Test
    void nonStreamingChatStillUsesTheNormalCall() {
        when(llm.create(anyString(), any(), any())).thenReturn(json(TEXT));

        String answer = chat.chat(ask("서울 지금 날씨"), "u1").content();

        assertEquals("서울은 18.5도예요.", answer);
        verify(llm, never()).stream(anyString(), any(), any(), any());
    }
}
