package com.example.weather.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    /** 응답 JSON 전체(content, stop_reason, usage)를 돌려준다. temperature 등 샘플링 값은 보내지 않는다(모델 기본값 사용). */
    public JsonNode create(String system, JsonNode tools, JsonNode messages) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.model());
        body.put("max_tokens", props.maxTokens());
        body.put("system", system);
        body.set("tools", tools);
        body.set("messages", messages);
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
