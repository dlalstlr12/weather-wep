package com.example.weather.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * KmaClient의 현재 동작(리팩토링 전 기준선)을 고정하는 특성화 테스트.
 * 기상청은 WireMock으로 대체한다. 타임아웃·재시도가 없다는 사실도 여기서 문서화한다.
 */
class KmaClientCharacterizationTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    private KmaClient client() {
        return KmaFixtures.client(wm.baseUrl());
    }

    @Test
    void ultraNowcastIsParsedIntoCategoryMap() {
        wm.stubFor(get(urlPathEqualTo("/getUltraSrtNcst")).willReturn(okJson(KmaFixtures.ULTRA_OK)));
        Map<String, String> r = client().getUltraNowcast(37.5665, 126.9780, true);
        assertEquals("18.5", r.get("T1H"));
        assertEquals("0", r.get("RN1"));
        assertEquals("55", r.get("REH"));
    }

    @Test
    void vilageForecastIsGroupedByDateTime() {
        wm.stubFor(get(urlPathEqualTo("/getVilageFcst")).willReturn(okJson(KmaFixtures.VILAGE_OK)));
        List<Map<String, String>> r = client().getVilageForecast(37.5665, 126.9780, true);
        assertEquals(2, r.size());
        Map<String, String> noon = r.stream().filter(m -> "202610141200".equals(m.get("dateTime"))).findFirst().orElseThrow();
        assertEquals("20", noon.get("TMP"));
        assertEquals("30", noon.get("POP"));
        assertEquals("3", noon.get("SKY"));
    }

    @Test
    void requestCarriesGridAndBaseTimeParams() {
        wm.stubFor(get(urlPathEqualTo("/getVilageFcst")).willReturn(okJson(KmaFixtures.VILAGE_OK)));
        wm.stubFor(get(urlPathEqualTo("/getUltraSrtNcst")).willReturn(okJson(KmaFixtures.ULTRA_OK)));
        client().getVilageForecast(37.5665, 126.9780, true);
        client().getUltraNowcast(37.5665, 126.9780, true);
        // 서울 격자 60,127 / 단기예보 발표시각은 02·05·08·11·14·17·20·23시 정시 / 초단기실황은 HH30
        wm.verify(getRequestedFor(urlPathEqualTo("/getVilageFcst"))
                .withQueryParam("nx", equalTo("60")).withQueryParam("ny", equalTo("127"))
                .withQueryParam("base_time", matching("(02|05|08|11|14|17|20|23)00")));
        wm.verify(getRequestedFor(urlPathEqualTo("/getUltraSrtNcst"))
                .withQueryParam("base_time", matching("\\d\\d30")));
    }

    @Test
    void secondCallIsServedFromCacheUnlessNocache() {
        wm.stubFor(get(urlPathEqualTo("/getUltraSrtNcst")).willReturn(okJson(KmaFixtures.ULTRA_OK)));
        KmaClient c = client();
        c.getUltraNowcast(37.5665, 126.9780, false);
        c.getUltraNowcast(37.5665, 126.9780, false);
        wm.verify(1, getRequestedFor(urlPathEqualTo("/getUltraSrtNcst")));
        c.getUltraNowcast(37.5665, 126.9780, true);
        wm.verify(2, getRequestedFor(urlPathEqualTo("/getUltraSrtNcst")));
    }

    @Test
    void resultCodeErrorIsWrappedAsParseFailure() {
        wm.stubFor(get(urlPathEqualTo("/getUltraSrtNcst")).willReturn(okJson(KmaFixtures.RESULT_CODE_ERROR)));
        RuntimeException e = assertThrows(RuntimeException.class, () -> client().getUltraNowcast(37.5665, 126.9780, true));
        // 현재 동작: "KMA 오류"가 try 안에서 던져진 뒤 catch에서 "파싱 실패"로 다시 감싸진다 (오류 코드가 메시지에서 사라짐)
        assertEquals("KMA 응답 파싱 실패", e.getMessage());
        assertTrue(e.getCause().getMessage().contains("code=22"));
    }

    @Test
    void http500IsThrownAsRuntimeException() {
        wm.stubFor(get(urlPathEqualTo("/getUltraSrtNcst")).willReturn(aResponse().withStatus(500)));
        RuntimeException e = assertThrows(RuntimeException.class, () -> client().getUltraNowcast(37.5665, 126.9780, true));
        assertTrue(e.getMessage().contains("HTTP 500"));
        wm.verify(1, getRequestedFor(urlPathEqualTo("/getUltraSrtNcst"))); // 재시도 없음
    }

    @Test
    void malformedJsonThrows() {
        wm.stubFor(get(urlPathEqualTo("/getUltraSrtNcst")).willReturn(okJson("this is not json")));
        assertThrows(RuntimeException.class, () -> client().getUltraNowcast(37.5665, 126.9780, true));
    }

    @Test
    void noTimeoutTheCallerBlocksForTheWholeUpstreamDelay() {
        wm.stubFor(get(urlPathEqualTo("/getUltraSrtNcst"))
                .willReturn(okJson(KmaFixtures.ULTRA_OK).withFixedDelay(2000)));
        long t0 = System.nanoTime();
        client().getUltraNowcast(37.5665, 126.9780, true);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        // 현재 동작: 읽기 타임아웃이 없어 업스트림이 느린 만큼 그대로 기다린다
        assertTrue(elapsedMs >= 1900, "elapsed=" + elapsedMs);
    }

    @Test
    void blankServiceKeyFailsFast() {
        KmaClient c = new KmaClient("", "data", wm.baseUrl(), "http://unused", "/x", "header", "x-api-key", "serviceKey");
        assertThrows(IllegalStateException.class, () -> c.getUltraNowcast(37.5665, 126.9780, true));
    }
}
