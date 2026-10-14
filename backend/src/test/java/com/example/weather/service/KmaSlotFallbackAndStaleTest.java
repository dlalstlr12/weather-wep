package com.example.weather.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.weather.dto.WeatherDtos;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** 기준 시각 자료가 아직 없을 때 앞 슬롯으로 되돌아가기, 갱신 실패 시 지난 값(stale) 사용. */
class KmaSlotFallbackAndStaleTest {

    private static final String ULTRA = "/getUltraSrtNcst";
    private static final String VILAGE = "/getVilageFcst";

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    // 14시 05분: 초단기실황 기준은 13:30, 단기예보 기준은 14:00 (발표 직후라 자료가 아직 없을 수 있는 시각)
    private TestClock clock;

    @BeforeEach
    void setUp() {
        wm.resetAll();
        clock = TestClock.seoul("2026-10-14T14:05:00");
    }

    private KmaClient client(KmaProperties.Cache cache) {
        return new KmaClient(KmaFixtures.props(wm.baseUrl(), new KmaProperties.Http(500, 1000), cache,
                new KmaProperties.Resilience(1, 10, 100, 100, 50f, 1000)), clock);
    }

    private KmaClient client() {
        return client(new KmaProperties.Cache(600, 10800, 100));
    }

    @Test
    void ultraNowcastFallsBackToThePreviousSlotWhenTheCurrentOneHasNoData() {
        wm.stubFor(get(urlPathEqualTo(ULTRA)).withQueryParam("base_time", equalTo("1330"))
                .willReturn(okJson(KmaFixtures.resultCode("03", "NODATA_ERROR"))));
        wm.stubFor(get(urlPathEqualTo(ULTRA)).withQueryParam("base_time", equalTo("1230"))
                .willReturn(okJson(KmaFixtures.ULTRA_OK)));
        Map<String, String> r = client().getUltraNowcast(37.5665, 126.9780, true).value();
        assertEquals("18.5", r.get("T1H"));
        wm.verify(1, getRequestedFor(urlPathEqualTo(ULTRA)).withQueryParam("base_time", equalTo("1330")));
        wm.verify(1, getRequestedFor(urlPathEqualTo(ULTRA)).withQueryParam("base_time", equalTo("1230")));
    }

    @Test
    void vilageForecastFallsBackToThePreviousSlot() {
        wm.stubFor(get(urlPathEqualTo(VILAGE)).withQueryParam("base_time", equalTo("1400"))
                .willReturn(okJson(KmaFixtures.resultCode("03", "NODATA_ERROR"))));
        wm.stubFor(get(urlPathEqualTo(VILAGE)).withQueryParam("base_time", equalTo("1100"))
                .willReturn(okJson(KmaFixtures.VILAGE_OK)));
        List<Map<String, String>> r = client().getVilageForecast(37.5665, 126.9780, true).value();
        assertEquals(2, r.size());
    }

    @Test
    void emptyItemListIsTreatedAsNoDataToo() {
        String empty = "{\"response\":{\"header\":{\"resultCode\":\"00\",\"resultMsg\":\"OK\"},\"body\":{\"items\":{\"item\":[]}}}}";
        wm.stubFor(get(urlPathEqualTo(VILAGE)).withQueryParam("base_time", equalTo("1400")).willReturn(okJson(empty)));
        wm.stubFor(get(urlPathEqualTo(VILAGE)).withQueryParam("base_time", equalTo("1100"))
                .willReturn(okJson(KmaFixtures.VILAGE_OK)));
        assertEquals(2, client().getVilageForecast(37.5665, 126.9780, true).value().size());
    }

    @Test
    void whenThePreviousSlotHasNoDataEitherTheErrorIsNoData() {
        wm.stubFor(get(urlPathEqualTo(ULTRA)).willReturn(okJson(KmaFixtures.resultCode("03", "NODATA_ERROR"))));
        KmaApiException e = assertThrows(KmaApiException.class, () -> client().getUltraNowcast(37.5665, 126.9780, true));
        assertEquals(KmaApiException.Kind.NO_DATA, e.kind());
        wm.verify(2, getRequestedFor(urlPathEqualTo(ULTRA))); // 현재 슬롯 + 바로 앞 슬롯, 그 이상은 시도하지 않는다
    }

    @Test
    void otherResultCodesDoNotTriggerTheFallback() {
        wm.stubFor(get(urlPathEqualTo(ULTRA)).willReturn(okJson(KmaFixtures.RESULT_CODE_ERROR)));
        assertThrows(KmaApiException.class, () -> client().getUltraNowcast(37.5665, 126.9780, true));
        wm.verify(1, getRequestedFor(urlPathEqualTo(ULTRA)));
    }

    @Test
    void failedRefreshServesTheOldValueAsStaleThroughTheWeatherService() {
        wm.stubFor(get(urlPathEqualTo(ULTRA)).willReturn(okJson(KmaFixtures.ULTRA_WITH_SKY)));
        WeatherService service = new WeatherService(client(new KmaProperties.Cache(60, 10800, 100)), new LocationResolver(org.mockito.Mockito.mock(com.example.weather.location.LocationRepository.class)));

        WeatherDtos.CurrentWeatherResponse fresh = service.getCurrent(37.5665, 126.9780, null, false);
        assertFalse(fresh.stale());

        clock.advance(Duration.ofSeconds(120)); // ttl(60초) 경과, 같은 기준 시각 슬롯
        wm.stubFor(get(urlPathEqualTo(ULTRA)).willReturn(aResponse().withStatus(500)));
        WeatherDtos.CurrentWeatherResponse stale = service.getCurrent(37.5665, 126.9780, null, false);

        assertTrue(stale.stale());
        assertEquals(120, stale.ageSeconds());
        assertEquals(18.5, stale.temperature());
    }

    @Test
    void staleValueIsNotUsedBeyondTheStaleLimit() {
        wm.stubFor(get(urlPathEqualTo(ULTRA)).willReturn(okJson(KmaFixtures.ULTRA_WITH_SKY)));
        KmaClient c = client(new KmaProperties.Cache(60, 600, 100)); // 10분까지만 지난 값 허용
        c.getUltraNowcast(37.5665, 126.9780, false);
        clock.advance(Duration.ofSeconds(601));
        wm.stubFor(get(urlPathEqualTo(ULTRA)).willReturn(aResponse().withStatus(500)));
        assertThrows(KmaApiException.class, () -> c.getUltraNowcast(37.5665, 126.9780, false));
    }
}
