package com.example.weather.service;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.weather.dto.WeatherDtos;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.example.weather.location.LocationRepository;
import com.example.weather.location.Place;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** WeatherService의 현재 동작을 고정하는 특성화 테스트 (기상청은 WireMock). */
class WeatherServiceCharacterizationTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    private WeatherService service;

    @BeforeEach
    void setUp() {
        wm.resetAll();
        wm.stubFor(get(urlPathEqualTo("/getUltraSrtNcst")).willReturn(okJson(KmaFixtures.ULTRA_WITH_SKY)));
        wm.stubFor(get(urlPathEqualTo("/getVilageFcst")).willReturn(okJson(KmaFixtures.VILAGE_OK)));
        LocationRepository repo = mock(LocationRepository.class);
        // 위치 테이블에서 이름으로 찾은 결과 (기상청 공식 격자)
        Place busan = new Place(1, "부산광역시", "부산광역시", null, null, 98, 76, 35.1770, 129.0770, null);
        Place daegu = new Place(2, "대구광역시", "대구광역시", null, null, 89, 90, 35.8685, 128.6036, null);
        when(repo.findBestMatch("부산")).thenReturn(Optional.of(busan));
        when(repo.findBestMatch("부산광역시")).thenReturn(Optional.of(busan));
        when(repo.findBestMatch("대구")).thenReturn(Optional.of(daegu));
        service = new WeatherService(KmaFixtures.client(wm.baseUrl()), new LocationResolver(repo));
    }

    @Test
    void currentMapsNowcastFields() {
        WeatherDtos.CurrentWeatherResponse r = service.getCurrent(37.5665, 126.9780, null, true);
        assertEquals(18.5, r.temperature());
        assertEquals(0.0, r.precipitation());
        assertEquals("맑음", r.sky());
    }

    /** Phase 1 변경: 실황 응답에 SKY가 없어도 NPE 없이 하늘 상태를 "-"로 처리한다 (Phase 0에서는 NullPointerException). */
    @Test
    void currentWithoutSkyCategoryFallsBackToDash() {
        wm.stubFor(get(urlPathEqualTo("/getUltraSrtNcst")).willReturn(okJson(KmaFixtures.ULTRA_OK)));
        WeatherDtos.CurrentWeatherResponse r = service.getCurrent(37.5665, 126.9780, null, true);
        assertEquals("-", r.sky());
        assertEquals(18.5, r.temperature());
    }

    @Test
    void forecastMapsEntries() {
        WeatherDtos.ForecastResponse r = service.getForecast(37.5665, 126.9780, null, true);
        assertEquals(2, r.items().size());
        var noon = r.items().stream().filter(i -> "202610141200".equals(i.dateTime())).findFirst().orElseThrow();
        assertEquals(20.0, noon.temperature());
        assertEquals(30, noon.pop());
        assertEquals("구름많음", noon.sky());
    }

    @Test
    void busanCityNameUsesBusanCoordinates() {
        service.getCurrent(null, null, "부산", true);
        wm.verify(getRequestedFor(urlPathEqualTo("/getUltraSrtNcst"))
                .withQueryParam("nx", equalTo("98")).withQueryParam("ny", equalTo("76")));
    }

    /**
     * Phase 1 변경: 부산이 아닌 도시도 자기 위치로 조회한다 (Phase 0에서는 대구 요청이 서울 좌표 60,127로 조회됐다).
     * Phase 3 변경: 좌표에서 계산하지 않고 위치 테이블의 기상청 공식 격자를 쓴다 (대구: 계산값 89,91 대신 공식 89,90).
     */
    @Test
    void cityNameUsesTheOfficialGridFromTheLocationTable() {
        service.getCurrent(null, null, "대구", true);
        wm.verify(getRequestedFor(urlPathEqualTo("/getUltraSrtNcst"))
                .withQueryParam("nx", equalTo("89")).withQueryParam("ny", equalTo("90")));
    }

    /** Phase 1 변경: 지원하지 않는 도시는 서울 날씨를 돌려주는 대신 오류로 알린다. */
    @Test
    void unknownCityIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.getCurrent(null, null, "제주", true));
        wm.verify(0, getRequestedFor(urlPathEqualTo("/getUltraSrtNcst")));
    }

    /** Phase 1 변경: 좌표와 도시명이 함께 오면 좌표가 우선한다 (Phase 0에서는 도시명이 좌표를 덮어썼다). */
    @Test
    void explicitCoordinatesWinOverCityName() {
        service.getCurrent(35.1796, 129.0756, "서울", true);
        wm.verify(getRequestedFor(urlPathEqualTo("/getUltraSrtNcst"))
                .withQueryParam("nx", equalTo("98")).withQueryParam("ny", equalTo("76")));
    }

    @Test
    void fullOfficialNameIsAcceptedToo() {
        service.getCurrent(null, null, "부산광역시", true);
        wm.verify(getRequestedFor(urlPathEqualTo("/getUltraSrtNcst"))
                .withQueryParam("nx", equalTo("98")).withQueryParam("ny", equalTo("76")));
    }

    @Test
    void explicitCoordinatesAreUsedWhenNoCity() {
        service.getCurrent(35.1796, 129.0756, null, true);
        wm.verify(getRequestedFor(urlPathEqualTo("/getUltraSrtNcst"))
                .withQueryParam("nx", equalTo("98")).withQueryParam("ny", equalTo("76")));
    }

    @Test
    void missingLocationThrowsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> service.getCurrent(null, null, null, true));
        assertThrows(IllegalArgumentException.class, () -> service.getForecast(null, null, " ", true));
    }
}
