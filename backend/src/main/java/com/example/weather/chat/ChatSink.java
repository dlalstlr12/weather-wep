package com.example.weather.chat;

/** 챗 응답을 받는 쪽(SSE 전송 등). 호출하는 쪽 연결이 끊기면 StreamClosedException을 던져 모델 호출을 멈추게 한다. */
public interface ChatSink {

    /** 도구를 쓰는 동안의 진행 상태 ("현재 날씨를 조회하는 중이에요") */
    void status(String text);

    /** 답변 글자 조각 */
    void delta(String text);

    /** 지금까지 보낸 답변 조각을 지운다 (도구를 쓰기 전에 흘러나온 문장, 규칙 기반 답변으로 바뀔 때) */
    void reset();
}
