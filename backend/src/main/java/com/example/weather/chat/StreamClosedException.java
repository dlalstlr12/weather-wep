package com.example.weather.chat;

/** 응답을 받던 쪽(브라우저)이 연결을 끊었다. 더 이상 모델을 부르지 않고 조용히 끝낸다. */
public class StreamClosedException extends RuntimeException {
    public StreamClosedException(Throwable cause) {
        super("클라이언트 연결이 끊어졌습니다", cause);
    }
}
