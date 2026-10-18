package com.example.weather.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Anthropic Messages API 호출 (POST /v1/messages). 재시도는 하지 않는다 — 실패하면 호출자가 규칙 기반 답변으로 대체한다(재시도는 비용). */
@Slf4j
@Component
public class AnthropicClient {

    private final AnthropicProperties props;
    private final ObjectMapper mapper;
    private final RestClient http;

    public AnthropicClient(AnthropicProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.connectTimeoutMs());
        factory.setReadTimeout(props.readTimeoutMs());
        this.http = RestClient.builder().requestFactory(factory).baseUrl(props.baseUrl()).build();
    }

    public boolean enabled() {
        return props.enabled();
    }

    private ObjectNode requestBody(String system, JsonNode tools, JsonNode messages) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.model());
        body.put("max_tokens", props.maxTokens());
        body.put("system", system);
        body.set("tools", tools);
        body.set("messages", messages);
        return body;
    }

    /** 스트리밍 중 일어나는 일을 알려 받는다. */
    public interface StreamHandler {
        /** 답변 글자 조각이 도착했다 */
        void onText(String text);

        /** 모델이 도구 호출을 시작했다 (입력이 다 만들어지기 전) */
        default void onToolStart(String name) {}
    }

    /**
     * 스트리밍(stream=true)으로 호출한다. 글자 조각과 도구 호출 시작은 handler로 흘려 보내고,
     * 마지막에는 create()와 같은 모양(content, stop_reason, usage)의 응답을 조립해서 돌려준다.
     */
    public JsonNode stream(String system, JsonNode tools, JsonNode messages, StreamHandler handler) {
        ObjectNode body = requestBody(system, tools, messages);
        body.put("stream", true);
        try {
            return http.post().uri("/v1/messages")
                    .header("x-api-key", props.apiKey())
                    .header("anthropic-version", "2023-06-01")
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            String detail = new String(response.getBody().readNBytes(400), StandardCharsets.UTF_8);
                            log.warn("Anthropic 스트림 호출 실패 status={} body={}", response.getStatusCode().value(), detail);
                            throw new LlmException("Anthropic 호출 실패 status=" + response.getStatusCode().value());
                        }
                        return readStream(response.getBody(), handler);
                    });
        } catch (LlmException | StreamClosedException e) {
            throw e;
        } catch (RestClientException | UncheckedIOException e) {
            throw new LlmException("Anthropic 스트림 호출 실패: " + e.getClass().getSimpleName(), e);
        }
    }

    /** SSE 이벤트를 읽어 응답을 조립한다. 이벤트 종류는 데이터의 type 필드로 구분한다. */
    JsonNode readStream(InputStream in, StreamHandler handler) {
        Map<Integer, ObjectNode> blocks = new TreeMap<>();
        Map<Integer, StringBuilder> toolInputs = new TreeMap<>();
        int inputTokens = 0;
        int outputTokens = 0;
        String stopReason = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(line.substring(5).stripLeading());
                    continue;
                }
                if (!line.isEmpty()) continue; // event: 줄과 주석은 쓰지 않는다 (type으로 구분)
                if (data.length() == 0) continue;
                JsonNode d = mapper.readTree(data.toString());
                data.setLength(0);
                switch (d.path("type").asText()) {
                    case "message_start" -> inputTokens = d.path("message").path("usage").path("input_tokens").asInt();
                    case "content_block_start" -> {
                        int idx = d.path("index").asInt();
                        ObjectNode block = d.path("content_block").deepCopy();
                        if ("text".equals(block.path("type").asText())) block.put("text", "");
                        if ("tool_use".equals(block.path("type").asText())) {
                            toolInputs.put(idx, new StringBuilder());
                            handler.onToolStart(block.path("name").asText());
                        }
                        blocks.put(idx, block);
                    }
                    case "content_block_delta" -> {
                        int idx = d.path("index").asInt();
                        JsonNode delta = d.path("delta");
                        ObjectNode block = blocks.get(idx);
                        if (block == null) break;
                        if ("text_delta".equals(delta.path("type").asText())) {
                            String text = delta.path("text").asText();
                            block.put("text", block.path("text").asText() + text);
                            handler.onText(text);
                        } else if ("input_json_delta".equals(delta.path("type").asText())) {
                            toolInputs.get(idx).append(delta.path("partial_json").asText());
                        }
                    }
                    case "content_block_stop" -> {
                        int idx = d.path("index").asInt();
                        StringBuilder json = toolInputs.get(idx);
                        if (json != null) blocks.get(idx).set("input", mapper.readTree(json.length() == 0 ? "{}" : json.toString()));
                    }
                    case "message_delta" -> {
                        stopReason = d.path("delta").path("stop_reason").asText(stopReason);
                        outputTokens = d.path("usage").path("output_tokens").asInt(outputTokens);
                    }
                    case "error" -> throw new LlmException("Anthropic 스트림 오류: " + d.path("error").path("type").asText());
                    default -> { /* ping, message_stop 등 */ }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (stopReason == null) throw new LlmException("Anthropic 스트림이 중간에 끊겼습니다");

        ObjectNode res = mapper.createObjectNode();
        ArrayNode content = res.putArray("content");
        blocks.values().forEach(content::add);
        res.put("stop_reason", stopReason);
        ObjectNode usage = res.putObject("usage");
        usage.put("input_tokens", inputTokens);
        usage.put("output_tokens", outputTokens);
        return res;
    }

    /** 응답 JSON 전체(content, stop_reason, usage)를 돌려준다. temperature 등 샘플링 값은 보내지 않는다(모델 기본값 사용). */
    public JsonNode create(String system, JsonNode tools, JsonNode messages) {
        ObjectNode body = requestBody(system, tools, messages);
        try {
            JsonNode res = http.post().uri("/v1/messages")
                    .header("x-api-key", props.apiKey())
                    .header("anthropic-version", "2023-06-01")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (res == null || !res.has("content")) throw new LlmException("Anthropic 응답 형식 오류");
            return res;
        } catch (RestClientResponseException e) {
            // 응답 본문에는 요청 키가 들어 있지 않다. 원인(한도 초과, 잘못된 모델 등)을 알 수 있게 본문 일부만 남긴다
            String detail = e.getResponseBodyAsString();
            log.warn("Anthropic 호출 실패 status={} body={}", e.getStatusCode().value(),
                    detail.length() > 300 ? detail.substring(0, 300) : detail);
            throw new LlmException("Anthropic 호출 실패 status=" + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new LlmException("Anthropic 호출 실패: " + e.getClass().getSimpleName(), e);
        }
    }
}
