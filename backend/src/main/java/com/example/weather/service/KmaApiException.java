package com.example.weather.service;

/** 기상청 API 호출·응답 처리 실패. 종류별로 구분해 상위 계층(예외 핸들러, 재시도)이 판단할 수 있게 한다. */
public class KmaApiException extends RuntimeException {

    public enum Kind {
        /** 연결 실패, 타임아웃 등 응답을 받지 못함 */
        UNREACHABLE,
        /** HTTP 4xx/5xx */
        HTTP_ERROR,
        /** HTTP는 성공했지만 resultCode가 00이 아님 */
        RESULT_CODE,
        /** 본문이 JSON이 아니거나 예상한 구조가 아님 */
        PARSE_ERROR
    }

    private final Kind kind;
    private final String resultCode;

    public KmaApiException(Kind kind, String message, String resultCode, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.resultCode = resultCode;
    }

    public Kind kind() {
        return kind;
    }

    /** RESULT_CODE일 때 기상청이 준 코드, 그 외에는 null */
    public String resultCode() {
        return resultCode;
    }
}
