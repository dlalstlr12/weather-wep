package com.example.weather.chat;

/** LLM 호출 실패(네트워크, 4xx/5xx, 응답 형식 오류, 호출 횟수 초과). 잡아서 규칙 기반 답변으로 대체한다. */
public class LlmException extends RuntimeException {
    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
