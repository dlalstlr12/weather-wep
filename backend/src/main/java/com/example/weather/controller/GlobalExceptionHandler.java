package com.example.weather.controller;

import com.example.weather.service.KmaApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 컨트롤러 예외를 ProblemDetail(RFC 9457) 응답으로 통일한다.
 * 예외 핸들러가 없으면 서블릿 에러 디스패치(/error)로 넘어가 인증 필터에 막혀 403으로 보였다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(KmaApiException.class)
    ProblemDetail upstream(KmaApiException e) {
        log.warn("기상청 호출 실패 kind={} resultCode={} message={}", e.kind(), e.resultCode(), e.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "기상청 데이터를 가져오지 못했습니다.");
        pd.setTitle("기상청 응답 오류");
        pd.setProperty("kind", e.kind().name());
        return pd;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail internal(Exception e) {
        log.error("처리되지 않은 예외", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");
    }
}
