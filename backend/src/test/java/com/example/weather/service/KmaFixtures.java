package com.example.weather.service;

import java.time.Clock;

/** 기상청 응답 모양의 테스트 픽스처와 KmaClient/KmaProperties 생성 헬퍼. */
final class KmaFixtures {
    private KmaFixtures() {}

    static final String ULTRA_OK = """
            {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL_SERVICE"},
             "body":{"items":{"item":[
               {"category":"T1H","obsrValue":"18.5"},
               {"category":"RN1","obsrValue":"0"},
               {"category":"REH","obsrValue":"55"}
             ]}}}}
            """;

    /** 허브(provider=hub)처럼 SKY 카테고리가 포함된 응답. 실제 초단기실황(data.go.kr)에는 SKY가 없다. */
    static final String ULTRA_WITH_SKY = """
            {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL_SERVICE"},
             "body":{"items":{"item":[
               {"category":"T1H","obsrValue":"18.5"},
               {"category":"RN1","obsrValue":"0"},
               {"category":"SKY","obsrValue":"1"}
             ]}}}}
            """;

    static final String VILAGE_OK = """
            {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL_SERVICE"},
             "body":{"items":{"item":[
               {"fcstDate":"20261014","fcstTime":"1200","category":"TMP","fcstValue":"20"},
               {"fcstDate":"20261014","fcstTime":"1200","category":"POP","fcstValue":"30"},
               {"fcstDate":"20261014","fcstTime":"1200","category":"SKY","fcstValue":"3"},
               {"fcstDate":"20261014","fcstTime":"1500","category":"TMP","fcstValue":"22"},
               {"fcstDate":"20261014","fcstTime":"1500","category":"SKY","fcstValue":"1"}
             ]}}}}
            """;

    static final String RESULT_CODE_ERROR = resultCode("22", "LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR");

    static String resultCode(String code, String msg) {
        return "{\"response\":{\"header\":{\"resultCode\":\"" + code + "\",\"resultMsg\":\"" + msg + "\"}}}";
    }

    /** 테스트 기본값: 빠른 타임아웃, 재시도 없음, 서킷 브레이커는 사실상 비활성 */
    static KmaProperties props(String dataBaseUrl) {
        return props(dataBaseUrl, new KmaProperties.Http(500, 1000), new KmaProperties.Cache(600, 10800, 1000),
                new KmaProperties.Resilience(1, 10, 100, 100, 50f, 1000));
    }

    static KmaProperties props(String dataBaseUrl, KmaProperties.Http http, KmaProperties.Cache cache,
                               KmaProperties.Resilience resilience) {
        return new KmaProperties("test-key", "data", new KmaProperties.Data(dataBaseUrl),
                new KmaProperties.Hub("http://unused", new KmaProperties.Auth("header", "x-api-key", "serviceKey")),
                http, cache, resilience);
    }

    static KmaProperties hubProps(String hubBaseUrl, String authMode, String queryName) {
        return new KmaProperties("hub-key", "hub", new KmaProperties.Data("http://unused"),
                new KmaProperties.Hub(hubBaseUrl, new KmaProperties.Auth(authMode, "x-api-key", queryName)),
                new KmaProperties.Http(500, 1000), new KmaProperties.Cache(600, 10800, 1000),
                new KmaProperties.Resilience(1, 10, 100, 100, 50f, 1000));
    }

    static KmaClient client(String dataBaseUrl) {
        return new KmaClient(props(dataBaseUrl));
    }

    static KmaClient client(KmaProperties props, Clock clock) {
        return new KmaClient(props, clock);
    }
}
