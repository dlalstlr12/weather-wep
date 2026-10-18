package com.example.weather.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.weather.dto.WeatherDtos;
import com.example.weather.location.LocationRepository;
import com.example.weather.location.Place;
import com.example.weather.service.KmaApiException;
import com.example.weather.service.LocationResolver;
import com.example.weather.service.WeatherService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WeatherToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final WeatherTools.Context NO_CTX = new WeatherTools.Context(null, null, null);

    private LocationRepository locations;
    private LocationResolver resolver;
    private WeatherService weather;
    private WeatherTools tools;

    @BeforeEach
    void setUp() {
        locations = mock(LocationRepository.class);
        resolver = mock(LocationResolver.class);
        weather = mock(WeatherService.class);
        // 2026-10-14 10:00 KST
        Clock clock = Clock.fixed(Instant.parse("2026-10-14T01:00:00Z"), ZoneId.of("Asia/Seoul"));
        tools = new WeatherTools(locations, resolver, weather, MAPPER, clock);
    }

    private static JsonNode in(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static WeatherDtos.ForecastEntry entry(String dt, double t, int pop) {
        return new WeatherDtos.ForecastEntry(dt, t, 0.0, "맑음", pop, 50, 1.0);
    }

    @Test
    void definitionsDeclareThreeTools() {
        assertEquals(3, tools.definitions().size());
        assertEquals("search_location", tools.definitions().get(0).path("name").asText());
    }

    @Test
    void currentWeatherReturnsOnlyNeededFields() {
        when(resolver.resolve(null, null, "서울")).thenReturn(new LocationResolver.Location(37.5, 127.0, 60, 127, "서울특별시"));
        when(weather.getCurrent(null, null, "서울", false)).thenReturn(new WeatherDtos.CurrentWeatherResponse(18.5, 0.0, "맑음"));

        var r = tools.execute("get_current_weather", in("{\"location\":\"서울\"}"), NO_CTX);

        assertFalse(r.isError());
        assertEquals("{\"location\":\"서울특별시\",\"temp_c\":18.5,\"rain_mm\":0.0,\"sky\":\"맑음\"}", r.content());
    }

    @Test
    void staleDataCarriesItsAgeInMinutes() {
        when(resolver.resolve(null, null, "서울")).thenReturn(new LocationResolver.Location(37.5, 127.0, 60, 127, "서울특별시"));
        when(weather.getCurrent(null, null, "서울", false)).thenReturn(new WeatherDtos.CurrentWeatherResponse(18.5, 0.0, "맑음", true, 600));

        assertTrue(tools.execute("get_current_weather", in("{\"location\":\"서울\"}"), NO_CTX).content().contains("\"stale_minutes\":10"));
    }

    @Test
    void omittedLocationUsesRequestCoordinates() {
        when(weather.getCurrent(37.5, 127.0, null, false)).thenReturn(new WeatherDtos.CurrentWeatherResponse(1.0, 0.0, "흐림"));

        var r = tools.execute("get_current_weather", in("{}"), new WeatherTools.Context(null, 37.5, 127.0));

        assertFalse(r.isError());
        assertTrue(r.content().contains("\"temp_c\":1.0"));
    }

    @Test
    void noLocationAnywhereIsAnErrorForTheModel() {
        var r = tools.execute("get_current_weather", in("{}"), NO_CTX);
        assertTrue(r.isError());
        assertTrue(r.content().contains("no location"));
    }

    @Test
    void unknownPlaceIsAnErrorNotAnException() {
        when(resolver.resolve(null, null, "없는동")).thenThrow(new IllegalArgumentException("찾을 수 없는 지역입니다: 없는동"));
        var r = tools.execute("get_current_weather", in("{\"location\":\"없는동\"}"), NO_CTX);
        assertTrue(r.isError());
        assertTrue(r.content().contains("찾을 수 없는 지역"));
    }

    @Test
    void upstreamFailureIsAnErrorWithoutLeakingDetails() {
        when(resolver.resolve(null, null, "서울")).thenReturn(new LocationResolver.Location(37.5, 127.0, 60, 127, "서울특별시"));
        when(weather.getCurrent(null, null, "서울", false))
                .thenThrow(new KmaApiException(KmaApiException.Kind.UNREACHABLE, "timeout http://secret", null, null));
        var r = tools.execute("get_current_weather", in("{\"location\":\"서울\"}"), NO_CTX);
        assertTrue(r.isError());
        assertFalse(r.content().contains("secret"));
    }

    @Test
    void forecastIsFilteredToTheRequestedDayAndSampledEveryThreeHours() {
        when(resolver.resolve(null, null, "서울")).thenReturn(new LocationResolver.Location(37.5, 127.0, 60, 127, "서울특별시"));
        when(weather.getForecast(null, null, "서울", false)).thenReturn(new WeatherDtos.ForecastResponse(List.of(
                entry("202610140900", 9, 0),   // 오늘 → 제외
                entry("202610150000", 10, 0),  // 내일 0시
                entry("202610150100", 11, 0),  // 3시간 간격이 아니라 제외
                entry("202610150300", 8, 20),
                entry("202610151500", 20, 60))));

        var r = tools.execute("get_forecast", in("{\"location\":\"서울\",\"day_offset\":1}"), NO_CTX);

        assertFalse(r.isError());
        JsonNode out = in(r.content());
        assertEquals("20261015", out.path("date").asText());
        assertEquals(8.0, out.path("min_c").asDouble());
        assertEquals(20.0, out.path("max_c").asDouble());
        assertEquals(3, out.path("rows").size());
        assertEquals(15, out.path("rows").get(2).path("h").asInt());
        assertEquals(60, out.path("rows").get(2).path("pop").asInt());
    }

    @Test
    void dayWithoutForecastSaysSo() {
        when(resolver.resolve(null, null, "서울")).thenReturn(new LocationResolver.Location(37.5, 127.0, 60, 127, "서울특별시"));
        when(weather.getForecast(null, null, "서울", false)).thenReturn(new WeatherDtos.ForecastResponse(List.of()));
        var r = tools.execute("get_forecast", in("{\"location\":\"서울\",\"day_offset\":3}"), NO_CTX);
        assertFalse(r.isError());
        assertTrue(r.content().contains("no forecast"));
    }

    @Test
    void dayOffsetOutOfRangeIsRejected() {
        assertTrue(tools.execute("get_forecast", in("{\"location\":\"서울\",\"day_offset\":9}"), NO_CTX).isError());
    }

    @Test
    void searchLocationListsAtMostThreeNames() {
        when(locations.search("중구", 3)).thenReturn(List.of(
                new Place(1, "서울특별시 중구", "서울특별시", "중구", null, 60, 127, 37.5, 127.0, null),
                new Place(2, "부산광역시 중구", "부산광역시", "중구", null, 97, 74, 35.1, 129.0, null)));
        var r = tools.execute("search_location", in("{\"query\":\"중구\"}"), NO_CTX);
        assertEquals("{\"matches\":[\"서울특별시 중구\",\"부산광역시 중구\"]}", r.content());
    }

    @Test
    void unknownToolIsAnError() {
        assertTrue(tools.execute("delete_everything", in("{}"), NO_CTX).isError());
    }
}
