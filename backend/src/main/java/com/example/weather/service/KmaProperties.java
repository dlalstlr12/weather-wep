package com.example.weather.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 기상청 연동 설정 (application.yml의 kma.*). 값이 없으면 @DefaultValue가 쓰인다. */
@ConfigurationProperties(prefix = "kma")
public record KmaProperties(
        @DefaultValue("") String serviceKey,
        /** data(공공데이터포털) | hub(기상청 API Hub) */
        @DefaultValue("data") String provider,
        @DefaultValue Data data,
        @DefaultValue Hub hub,
        @DefaultValue Http http,
        @DefaultValue Cache cache,
        @DefaultValue Resilience resilience) {

    public record Data(
            @DefaultValue("https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0") String baseUrl) {}

    public record Hub(
            @DefaultValue("https://apihub.kma.go.kr") String baseUrl,
            @DefaultValue Auth auth) {}

    public record Auth(
            /** header | query */
            @DefaultValue("header") String mode,
            @DefaultValue("x-api-key") String headerName,
            @DefaultValue("serviceKey") String queryName) {}

    /** 연결/응답 대기 제한. 응답이 없으면 요청 스레드를 오래 붙잡지 않고 끊는다. */
    public record Http(
            @DefaultValue("1000") int connectTimeoutMs,
            @DefaultValue("2000") int readTimeoutMs) {}

    /** ttlSeconds 안에는 캐시를 그대로 쓰고, 그 이후 갱신에 실패하면 staleMaxSeconds까지는 지난 값을 대신 쓴다. */
    public record Cache(
            @DefaultValue("600") long ttlSeconds,
            @DefaultValue("10800") long staleMaxSeconds,
            @DefaultValue("1000") long maxSize) {}

    public record Resilience(
            /** 첫 시도를 포함한 총 시도 횟수 */
            @DefaultValue("2") int retryMaxAttempts,
            @DefaultValue("200") long retryWaitMs,
            @DefaultValue("20") int breakerWindowSize,
            @DefaultValue("10") int breakerMinimumCalls,
            @DefaultValue("50") float breakerFailureRatePercent,
            @DefaultValue("10000") long breakerOpenMs) {}
}
