package com.example.weather.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Anthropic Messages API 연동과 비용 상한 설정 (application.yml의 anthropic.*). apiKey가 비어 있으면 LLM 없이 기존 규칙 기반 답변만 쓴다. */
@ConfigurationProperties(prefix = "anthropic")
public record AnthropicProperties(
        @DefaultValue("") String apiKey,
        @DefaultValue("https://api.anthropic.com") String baseUrl,
        @DefaultValue("claude-haiku-5-5") String model,
        /** 응답 한 번에 생성할 수 있는 최대 토큰 (출력 비용 상한) */
        @DefaultValue("400") int maxTokens,
        @DefaultValue("2000") int connectTimeoutMs,
        @DefaultValue("20000") int readTimeoutMs,
        /** 한 질문에 모델을 부르는 최대 횟수 (도구 호출 왕복 포함). 넘으면 규칙 기반 답변으로 대체 */
        @DefaultValue("3") int maxModelCalls,
        /** 이전 대화는 최근 몇 개 메시지만 보낸다 */
        @DefaultValue("6") int maxHistory,
        /** 메시지 하나의 최대 글자 수. 넘으면 잘라서 보낸다 */
        @DefaultValue("500") int maxMessageChars,
        /** 하루(KST) 사용자당 LLM 사용 질문 수. 넘으면 규칙 기반 답변 */
        @DefaultValue("10") int dailyLimitPerUser,
        /** 하루(KST) 전체 LLM 사용 질문 수. 넘으면 규칙 기반 답변 */
        @DefaultValue("100") int dailyLimitTotal) {

    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }
}
