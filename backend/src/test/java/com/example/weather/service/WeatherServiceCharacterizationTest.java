package com.example.weather.service;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.weather.dto.WeatherDtos;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
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
        service = new WeatherService(KmaFixtures.client(wm.baseUrl()));
    }

    @Test
    void currentMapsNowcastFields() {
        WeatherDtos.CurrentWeatherResponse r = service.getCurrent(37.5665, 126.9780, null, true);
        assertEquals(18.5, r.temperature());
        assertEquals(0.0, r.precipitation());
        assertEquals("맑음", r.sky());
    }

    /**
     * 알려진 결함의 현재 동작 고정: 실제 초단기실황(data.go.kr) 응답에는 SKY 카테고리가 없는데,
     * skyCodeToText(null)이 switch에서 NullPointerException을 일으켜 provider=data에서는 현재 날씨 조회가 실패한다.
     * (배포 설정은 provider=hub라 단기예보 응답을 써서 드러나지 않았다.)
     */
    @Test
    void currentWithoutSkyCategoryThrowsNullPointer() {
        wm.stubFor(get(urlPathEqualTo("/getUltraSrtNcst")).willReturn(okJson(KmaFixtures.ULTRA_OK)));
        assertThrows(NullPointerException.class, () -> service.getCurrent(37.5665, 126.9780, null, true));
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
     * 알려진 결함의 현재 동작 고정: city가 비어 있지 않으면 부산이 아닌 모든 도시가 서울 좌표(60,127)로 조회된다.
     * (예: 대구, 제주 → 서울 날씨) 이후 단계에서 위치 해석을 고치면 이 테스트를 갱신한다.
     */
    @Test
    void anyOtherCityNameIsTreatedAsSeoul() {
        service.getCurrent(35.8714, 128.6014, "대구", true);
        wm.verify(getRequestedFor(urlPathEqualTo("/getUltraSrtNcst"))
                .withQueryParam("nx", equalTo("60")).withQueryParam("ny", equalTo("127")));
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
