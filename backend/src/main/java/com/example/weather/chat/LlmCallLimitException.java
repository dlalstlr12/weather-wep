package com.example.weather.chat;

/** 한 질문에 모델을 부를 수 있는 횟수를 넘었다. 일반 호출 실패와 구분해서 측정하려고 따로 둔다. */
public class LlmCallLimitException extends LlmException {
    public LlmCallLimitException(String message) {
        super(message);
    }
}
