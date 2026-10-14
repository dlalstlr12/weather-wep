package com.example.weather.service;

/** 기상청 응답 모양의 테스트 픽스처와 KmaClient 생성 헬퍼. */
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

    static final String RESULT_CODE_ERROR = """
            {"response":{"header":{"resultCode":"22","resultMsg":"LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR"}}}
            """;

    static KmaClient client(String baseUrl) {
        return new KmaClient("test-key", "data", baseUrl, "http://unused", "/x", "header", "x-api-key", "serviceKey");
    }
}
