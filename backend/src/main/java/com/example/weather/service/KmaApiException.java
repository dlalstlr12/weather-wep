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
        PARSE_ERROR,
        /** 해당 기준 시각의 자료가 아직 없음 (발표 직후 등) */
        NO_DATA,
        /** 서킷 브레이커가 열려 호출하지 않음 */
        CIRCUIT_OPEN
    }

    private final Kind kind;
    private final String resultCode;
    private final Integer httpStatus;

    public KmaApiException(Kind kind, String message, String resultCode, Throwable cause) {
        this(kind, message, resultCode, null, cause);
    }

    public KmaApiException(Kind kind, String message, String resultCode, Integer httpStatus, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.resultCode = resultCode;
        this.httpStatus = httpStatus;
    }

    public Kind kind() {
        return kind;
    }

    /** RESULT_CODE일 때 기상청이 준 코드, 그 외에는 null */
    public String resultCode() {
        return resultCode;
    }

    /** HTTP_ERROR일 때 응답 상태 코드, 그 외에는 null */
    public Integer httpStatus() {
        return httpStatus;
    }

    /** 잠시 뒤 다시 시도하면 성공할 수 있는 오류 (연결 실패, 5xx, 게이트웨이 타임아웃 05) */
    public boolean isRetryable() {
        return switch (kind) {
            case UNREACHABLE -> true;
            case HTTP_ERROR -> httpStatus != null && httpStatus >= 500;
            case RESULT_CODE -> "05".equals(resultCode);
            default -> false;
        };
    }

    /** 서킷 브레이커가 실패로 세는 오류. 우리 쪽 요청 문제(4xx, 파라미터·인증 코드)와 자료 없음은 제외한다. */
    public boolean countsAsUpstreamFailure() {
        return switch (kind) {
            case UNREACHABLE, PARSE_ERROR -> true;
            case HTTP_ERROR -> httpStatus == null || httpStatus >= 500;
            // 04 HTTP_ERROR, 05 게이트웨이 타임아웃, 22 일일 호출 초과, 23 초당 호출 초과
            case RESULT_CODE -> resultCode != null && java.util.Set.of("04", "05", "22", "23").contains(resultCode);
            default -> false;
        };
    }
}
