package com.example.weather.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.weather.dto.WeatherDtos;
import java.util.List;
import org.junit.jupiter.api.Test;

/** NlgFormatter, TextSanitizer의 현재 동작을 고정하는 특성화 테스트. 템플릿 선택은 해시 기반이라 포함 여부만 검사한다. */
class NlgFormatterAndSanitizerTest {

    @Test
    void currentIncludesCityTemperatureAndRainAdvice() {
        var cw = new WeatherDtos.CurrentWeatherResponse(18.5, 2.0, "맑음");
        String out = NlgFormatter.formatCurrent(cw, "서울");
        assertTrue(out.contains("서울"));
        assertTrue(out.contains("18.5°C"));
        assertTrue(out.contains("우산을 챙기면 좋아요."));
    }

    @Test
    void currentHotAndColdAdvice() {
        assertTrue(NlgFormatter.formatCurrent(new WeatherDtos.CurrentWeatherResponse(31.0, 0.0, "맑음"), "대구")
                .contains("더위에 유의하고 수분을 충분히 섭취하세요."));
        assertTrue(NlgFormatter.formatCurrent(new WeatherDtos.CurrentWeatherResponse(-2.0, 0.0, "맑음"), "대구")
                .contains("두꺼운 외투와 장갑을 준비하세요."));
    }

    @Test
    void currentWithoutCityUsesDefaultLocationWord() {
        String out = NlgFormatter.formatCurrent(new WeatherDtos.CurrentWeatherResponse(15.0, 0.0, "맑음"), null);
        assertTrue(out.contains("해당 지역"));
    }

    @Test
    void currentNullReturnsNull() {
        assertNull(NlgFormatter.formatCurrent(null, "서울"));
    }

    @Test
    void forecastAdviceByPrecipitationProbability() {
        var high = new WeatherDtos.ForecastResponse(List.of(
                new WeatherDtos.ForecastEntry("202601011200", 20.0, 0.0, "흐림", 70, 60, 1.0)));
        assertTrue(NlgFormatter.formatForecast(high, "서울", "내일", 0).contains("비 가능성이 높으니 우산을 준비하세요."));
        var mid = new WeatherDtos.ForecastResponse(List.of(
                new WeatherDtos.ForecastEntry("202601011200", 20.0, 0.0, "흐림", 40, 60, 1.0)));
        assertTrue(NlgFormatter.formatForecast(mid, "서울", "내일", 0).contains("소나기에 대비해 가벼운 우산을 고려하세요."));
    }

    @Test
    void forecastOutOfRangeIndexFallsBackToFirstItemAndEmptyReturnsNull() {
        var fr = new WeatherDtos.ForecastResponse(List.of(
                new WeatherDtos.ForecastEntry("202601011200", 20.0, 0.0, "맑음", 0, 60, 1.0)));
        assertTrue(NlgFormatter.formatForecast(fr, "서울", "내일", 5).contains("20.0°C"));
        assertNull(NlgFormatter.formatForecast(new WeatherDtos.ForecastResponse(List.of()), "서울", "내일", 0));
    }

    @Test
    void naturalSkyMapping() {
        assertEquals("맑음", NlgFormatter.naturalSky("-"));
        assertEquals("구름 많음", NlgFormatter.naturalSky("구름많음"));
        assertEquals("흐림", NlgFormatter.naturalSky("흐림"));
        assertEquals("비", NlgFormatter.naturalSky("rain"));
        assertEquals("눈", NlgFormatter.naturalSky("snow"));
    }

    @Test
    void sanitizerCollapsesWhitespaceAndRepeatedSymbols() {
        assertNull(TextSanitizer.sanitize(null));
        assertEquals("안녕 하세요", TextSanitizer.sanitize("안녕\t\r\n  하세요"));
        assertEquals("a b", TextSanitizer.sanitize("a >>>> b"));
        assertEquals("ab", TextSanitizer.sanitize("a====b")); // 3개 이상 연속은 먼저 통째로 제거됨
        assertEquals("a=b", TextSanitizer.sanitize("a==b"));
    }
}
