package com.example.weather.chat;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Anthropic 스트리밍(SSE) 응답을 읽어 글자 조각을 흘려 보내고 create()와 같은 모양으로 조립하는지 확인한다. */
class AnthropicStreamTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AnthropicClient client() {
        var props = new AnthropicProperties("secret-key", wm.baseUrl(), "claude-haiku-5-5", 400, 500, 2000, 4, 6, 500, 10, 100);
        return new AnthropicClient(props, MAPPER);
    }

    private static final String TEXT_THEN_TOOL = String.join("\n",
            "event: message_start",
            "data: {\"type\":\"message_start\",\"message\":{\"id\":\"m1\",\"role\":\"assistant\",\"content\":[],\"usage\":{\"input_tokens\":25,\"output_tokens\":1}}}",
            "",
            "event: content_block_start",
            "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
            "",
            "event: ping",
            "data: {\"type\":\"ping\"}",
            "",
            "event: content_block_delta",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"조회할게요. \"}}",
            "",
            "event: content_block_delta",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"잠시만요\"}}",
            "",
            "event: content_block_stop",
            "data: {\"type\":\"content_block_stop\",\"index\":0}",
            "",
            "event: content_block_start",
            "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"tu_1\",\"name\":\"get_current_weather\",\"input\":{}}}",
            "",
            "event: content_block_delta",
            "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\"}}",
            "",
            "event: content_block_delta",
            "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"locat\"}}",
            "",
            "event: content_block_delta",
            "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"ion\\\": \\\"서울\\\"}\"}}",
            "",
            "event: content_block_stop",
            "data: {\"type\":\"content_block_stop\",\"index\":1}",
            "",
            "event: message_delta",
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":42}}",
            "",
            "event: message_stop",
            "data: {\"type\":\"message_stop\"}",
            "", "");

    private void stubStream(String body) {
        wm.stubFor(post(urlPathEqualTo("/v1/messages")).willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream; charset=utf-8").withBody(body)));
    }

    private static class Recorder implements AnthropicClient.StreamHandler {
        final List<String> texts = new ArrayList<>();
        final List<String> tools = new ArrayList<>();

        @Override
        public void onText(String text) {
            texts.add(text);
        }

        @Override
        public void onToolStart(String name) {
            tools.add(name);
        }
    }

    private JsonNode stream(Recorder rec) throws Exception {
        return client().stream("sys", MAPPER.readTree("[]"), MAPPER.readTree("[{\"role\":\"user\",\"content\":\"hi\"}]"), rec);
    }

    @Test
    void textAndToolUseAreAssembledAndForwarded() throws Exception {
        stubStream(TEXT_THEN_TOOL);
        Recorder rec = new Recorder();

        JsonNode res = stream(rec);

        assertEquals(List.of("조회할게요. ", "잠시만요"), rec.texts);
        assertEquals(List.of("get_current_weather"), rec.tools);
        assertEquals("tool_use", res.path("stop_reason").asText());
        assertEquals("조회할게요. 잠시만요", res.path("content").get(0).path("text").asText());
        JsonNode tool = res.path("content").get(1);
        assertEquals("tu_1", tool.path("id").asText());
        assertEquals("서울", tool.path("input").path("location").asText()); // 조각난 JSON이 합쳐졌다
        assertEquals(25, res.path("usage").path("input_tokens").asInt());
        assertEquals(42, res.path("usage").path("output_tokens").asInt());
    }

    @Test
    void requestAsksForStreamingWithKeyAndVersion() throws Exception {
        stubStream(TEXT_THEN_TOOL);
        stream(new Recorder());
        wm.verify(postRequestedFor(urlPathEqualTo("/v1/messages"))
                .withHeader("x-api-key", equalTo("secret-key"))
                .withHeader("anthropic-version", equalTo("2023-06-01"))
                .withHeader("Accept", equalTo("text/event-stream"))
                .withRequestBody(matchingJsonPath("$.stream", equalTo("true"))));
    }

    @Test
    void toolUseWithoutInputDeltasGetsAnEmptyObject() throws Exception {
        stubStream(String.join("\n",
                "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":3}}}", "",
                "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"get_forecast\",\"input\":{}}}", "",
                "data: {\"type\":\"content_block_stop\",\"index\":0}", "",
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":5}}", "", ""));
        JsonNode res = stream(new Recorder());
        assertTrue(res.path("content").get(0).path("input").isObject());
        assertEquals(0, res.path("content").get(0).path("input").size());
    }

    @Test
    void httpErrorBecomesLlmExceptionWithoutLeakingTheKey() {
        wm.stubFor(post(urlPathEqualTo("/v1/messages")).willReturn(aResponse().withStatus(429)
                .withBody("{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\"}}")));
        LlmException e = assertThrows(LlmException.class, () -> stream(new Recorder()));
        assertTrue(e.getMessage().contains("429"));
        assertFalse(e.getMessage().contains("secret-key"));
    }

    @Test
    void streamCutOffBeforeTheEndIsAnError() {
        stubStream(String.join("\n",
                "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":3}}}", "",
                "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}", "",
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"부분\"}}", "", ""));
        assertThrows(LlmException.class, () -> stream(new Recorder()));
    }

    @Test
    void errorEventInTheMiddleOfTheStreamIsAnError() {
        stubStream(String.join("\n",
                "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":3}}}", "",
                "data: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}", "", ""));
        LlmException e = assertThrows(LlmException.class, () -> stream(new Recorder()));
        assertTrue(e.getMessage().contains("overloaded_error"));
    }
}
