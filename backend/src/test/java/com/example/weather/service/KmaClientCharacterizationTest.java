package com.example.weather.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        Map<String, String> r = client().getUltraNowcast(37.5665, 126.9780, true).value();
        assertEquals("18.5", r.get("T1H"));
        assertEquals("0", r.get("RN1"));
        assertEquals("55", r.get("REH"));
    }

    @Test
    void vilageForecastIsGroupedByDateTime() {
        wm.stubFor(get(urlPathEqualTo("/getVilageFcst")).willReturn(okJson(KmaFixtures.VILAGE_OK)));
        List<Map<String, String>> r = client().getVilageForecast(37.5665, 126.9780, true).value();
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

    /** Phase 1 변경: resultCode 오류가 파싱 실패로 감싸지지 않고 코드가 그대로 전달된다 (Phase 0에서는 "KMA 응답 파싱 실패"로 바뀌어 코드가 사라졌다). */
    @Test
    void resultCodeErrorKeepsTheCode() {
        wm.stubFor(get(urlPathEqualTo("/getUltraSrtNcst")).willReturn(okJson(KmaFixtures.RESULT_CODE_ERROR)));
        KmaApiException e = assertThrows(KmaApiException.class, () -> client().getUltraNowcast(37.5665, 126.9780, true));
        assertEquals(KmaApiException.Kind.RESULT_CODE, e.kind());
        assertEquals("22", e.resultCode());
        assertTrue(e.getMessage().contains("code=22"));
    }

    @Test
    void http500IsThrownAsHttpErrorWithoutLeakingTheServiceKey() {
        wm.stubFor(get(urlPathEqualTo("/getUltraSrtNcst")).willReturn(aResponse().withStatus(500)));
        KmaApiException e = assertThrows(KmaApiException.class, () -> client().getUltraNowcast(37.5665, 126.9780, true));
        assertEquals(KmaApiException.Kind.HTTP_ERROR, e.kind());
        assertTrue(e.getMessage().contains("HTTP 500"));
        // Phase 0에서는 메시지에 서비스키가 들어 있는 전체 URL이 포함됐다
        assertFalse(e.getMessage().contains("test-key"));
        wm.verify(1, getRequestedFor(urlPathEqualTo("/getUltraSrtNcst"))); // 재시도 없음
    }

    @Test
    void malformedJsonIsParseError() {
        wm.stubFor(get(urlPathEqualTo("/getUltraSrtNcst")).willReturn(okJson("this is not json")));
        KmaApiException e = assertThrows(KmaApiException.class, () -> client().getUltraNowcast(37.5665, 126.9780, true));
        assertEquals(KmaApiException.Kind.PARSE_ERROR, e.kind());
    }

    @Test
    void unexpectedJsonStructureIsParseError() {
        wm.stubFor(get(urlPathEqualTo("/getUltraSrtNcst")).willReturn(okJson("{\"foo\":1}")));
        KmaApiException e = assertThrows(KmaApiException.class, () -> client().getUltraNowcast(37.5665, 126.9780, true));
        assertEquals(KmaApiException.Kind.PARSE_ERROR, e.kind());
    }

    @Test
    void connectionRefusedIsUnreachable() {
        KmaClient c = KmaFixtures.client("http://localhost:1"); // 아무도 듣지 않는 포트
        KmaApiException e = assertThrows(KmaApiException.class, () -> c.getUltraNowcast(37.5665, 126.9780, true));
        assertEquals(KmaApiException.Kind.UNREACHABLE, e.kind());
    }

    @Test
    void hubProviderSendsKeyInHeaderAndUsesVilageEndpointForNowcast() {
        wm.stubFor(get(urlPathEqualTo("/getVilageFcst")).willReturn(okJson(KmaFixtures.ULTRA_WITH_SKY)));
        KmaClient hub = new KmaClient(KmaFixtures.hubProps(wm.baseUrl(), "header", "serviceKey"));
        Map<String, String> r = hub.getUltraNowcast(37.5665, 126.9780, true).value();
        assertEquals("18.5", r.get("T1H"));
        wm.verify(getRequestedFor(urlPathEqualTo("/getVilageFcst")).withHeader("x-api-key", equalTo("hub-key")));
    }

    @Test
    void hubProviderCanSendKeyAsQueryParam() {
        wm.stubFor(get(urlPathEqualTo("/getVilageFcst")).willReturn(okJson(KmaFixtures.VILAGE_OK)));
        KmaClient hub = new KmaClient(KmaFixtures.hubProps(wm.baseUrl(), "query", "authKey"));
        hub.getVilageForecast(37.5665, 126.9780, true);
        wm.verify(getRequestedFor(urlPathEqualTo("/getVilageFcst")).withQueryParam("authKey", equalTo("hub-key")));
    }

    /** Phase 2 변경: 읽기 타임아웃이 있어 업스트림이 느려도 설정한 시간(1초) 안에 끊는다 (Phase 0에서는 지연 전체를 기다렸다). */
    @Test
    void slowUpstreamIsCutOffAtTheReadTimeout() {
        wm.stubFor(get(urlPathEqualTo("/getUltraSrtNcst"))
                .willReturn(okJson(KmaFixtures.ULTRA_OK).withFixedDelay(3000)));
        long t0 = System.nanoTime();
        KmaApiException e = assertThrows(KmaApiException.class, () -> client().getUltraNowcast(37.5665, 126.9780, true));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(KmaApiException.Kind.UNREACHABLE, e.kind());
        assertTrue(elapsedMs < 2000, "elapsed=" + elapsedMs);
    }

    @Test
    void blankServiceKeyFailsFast() {
        KmaProperties blank = new KmaProperties("", "data", new KmaProperties.Data(wm.baseUrl()), KmaFixtures.props(wm.baseUrl()).hub(),
                new KmaProperties.Http(500, 1000), new KmaProperties.Cache(600, 10800, 1000),
                new KmaProperties.Resilience(1, 10, 100, 100, 50f, 1000));
        KmaClient c = new KmaClient(blank);
        assertThrows(IllegalStateException.class, () -> c.getUltraNowcast(37.5665, 126.9780, true));
    }
}
