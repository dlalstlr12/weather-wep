package com.example.weather.chat;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Anthropic API는 WireMock으로 대체한다 (과금 없음). 요청 형식과 오류 처리를 확인한다. */
class AnthropicClientTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AnthropicClient client(int readTimeoutMs) {
        var props = new AnthropicProperties("secret-key", wm.baseUrl(), "claude-haiku-5-5", 400, 500, readTimeoutMs, 3, 6, 500, 10, 100);
        return new AnthropicClient(props, MAPPER);
    }

    private JsonNode call(AnthropicClient c) throws Exception {
        return c.create("sys", MAPPER.readTree("[]"), MAPPER.readTree("[{\"role\":\"user\",\"content\":\"hi\"}]"));
    }

    @Test
    void sendsKeyVersionAndModelWithoutSamplingParameters() throws Exception {
        wm.stubFor(post(urlPathEqualTo("/v1/messages")).willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody("{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":5,\"output_tokens\":2}}")));

        JsonNode res = call(client(2000));

        assertEquals("ok", res.path("content").get(0).path("text").asText());
        wm.verify(postRequestedFor(urlPathEqualTo("/v1/messages"))
                .withHeader("x-api-key", equalTo("secret-key"))
                .withHeader("anthropic-version", equalTo("2023-06-01"))
                .withRequestBody(equalToJson("{\"model\":\"claude-haiku-5-5\",\"max_tokens\":400,\"system\":\"sys\",\"tools\":[],"
                        + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")));
    }

    @Test
    void httpErrorBecomesLlmExceptionWithoutLeakingTheKey() {
        wm.stubFor(post(urlPathEqualTo("/v1/messages")).willReturn(aResponse().withStatus(429)
                .withBody("{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}")));

        LlmException e = assertThrows(LlmException.class, () -> call(client(2000)));

        assertTrue(e.getMessage().contains("429"));
        assertFalse(e.getMessage().contains("secret-key"));
    }

    @Test
    void slowResponseTimesOut() {
        wm.stubFor(post(urlPathEqualTo("/v1/messages")).willReturn(aResponse().withFixedDelay(1500).withStatus(200).withBody("{}")));
        assertThrows(LlmException.class, () -> call(client(300)));
    }

    @Test
    void bodyWithoutContentIsRejected() {
        wm.stubFor(post(urlPathEqualTo("/v1/messages")).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{\"foo\":1}")));
        assertThrows(LlmException.class, () -> call(client(2000)));
    }
}
