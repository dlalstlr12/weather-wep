package com.example.weather.chat;

import com.example.weather.dto.WeatherDtos;
import com.example.weather.location.LocationRepository;
import com.example.weather.location.Place;
import com.example.weather.service.KmaApiException;
import com.example.weather.service.LocationResolver;
import com.example.weather.service.WeatherService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 모델에 노출하는 도구 3개의 정의와 실행. 입력 토큰을 아끼려고 정의는 짧게, 결과는 필요한 값만 담은 작은 JSON으로 돌려준다.
 * 도구 실패는 예외가 아니라 isError 결과로 돌려줘서 모델이 사용자에게 상황을 설명하게 한다.
 */
@Slf4j
@Component
public class WeatherTools {

    /** 요청에서 온 기본 위치. 도구의 location 인자가 없으면 이 값을 쓴다. */
    public record Context(String city, Double lat, Double lon) {}

    public record Result(String content, boolean isError) {}

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int FORECAST_HOUR_STEP = 3;

    private final LocationRepository locations;
    private final LocationResolver resolver;
    private final WeatherService weather;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final JsonNode definitions;

    @Autowired
    public WeatherTools(LocationRepository locations, LocationResolver resolver, WeatherService weather, ObjectMapper mapper) {
        this(locations, resolver, weather, mapper, Clock.system(SEOUL));
    }

    WeatherTools(LocationRepository locations, LocationResolver resolver, WeatherService weather, ObjectMapper mapper, Clock clock) {
        this.locations = locations;
        this.resolver = resolver;
        this.weather = weather;
        this.mapper = mapper;
        this.clock = clock;
        this.definitions = buildDefinitions();
    }

    public JsonNode definitions() {
        return definitions;
    }

    public Result execute(String name, JsonNode input, Context ctx) {
        try {
            return switch (name) {
                case "search_location" -> searchLocation(input.path("query").asText(""));
                case "get_current_weather" -> currentWeather(input.path("location").asText(null), ctx);
                case "get_forecast" -> forecast(input.path("location").asText(null), input.path("day_offset").asInt(0), ctx);
                default -> new Result("unknown tool: " + name, true);
            };
        } catch (IllegalArgumentException e) {
            return new Result(e.getMessage(), true);
        } catch (KmaApiException e) {
            log.warn("도구 실행 중 기상청 호출 실패 tool={} kind={}", name, e.kind());
            return new Result("weather data is temporarily unavailable", true);
        } catch (JsonProcessingException e) {
            return new Result("internal error", true);
        }
    }

    private Result searchLocation(String query) throws JsonProcessingException {
        if (query.isBlank()) return new Result("query is required", true);
        List<String> names = locations.search(query, 3).stream().map(Place::fullName).toList();
        return ok(Map.of("matches", names));
    }

    private Result currentWeather(String location, Context ctx) throws JsonProcessingException {
        Target t = target(location, ctx);
        WeatherDtos.CurrentWeatherResponse r = weather.getCurrent(t.lat, t.lon, t.city, false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("location", t.label);
        put(out, "temp_c", r.temperature());
        put(out, "rain_mm", r.precipitation());
        put(out, "sky", r.sky());
        if (r.stale()) out.put("stale_minutes", r.ageSeconds() / 60);
        return ok(out);
    }

    private Result forecast(String location, int dayOffset, Context ctx) throws JsonProcessingException {
        if (dayOffset < 0 || dayOffset > 3) return new Result("day_offset must be between 0 and 3", true);
        Target t = target(location, ctx);
        WeatherDtos.ForecastResponse r = weather.getForecast(t.lat, t.lon, t.city, false);
        String day = LocalDate.now(clock).plusDays(dayOffset).format(YMD);
        List<WeatherDtos.ForecastEntry> items = r.items().stream()
                .filter(e -> e.dateTime() != null && e.dateTime().startsWith(day)).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("location", t.label);
        out.put("date", day);
        if (items.isEmpty()) {
            out.put("note", "no forecast for this date");
            return ok(out);
        }
        DoubleSummaryStatistics temps = items.stream().filter(e -> e.temperature() != null)
                .mapToDouble(WeatherDtos.ForecastEntry::temperature).summaryStatistics();
        if (temps.getCount() > 0) {
            out.put("min_c", temps.getMin());
            out.put("max_c", temps.getMax());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WeatherDtos.ForecastEntry e : items) {
            int hour = Integer.parseInt(e.dateTime().substring(8, 10));
            if (hour % FORECAST_HOUR_STEP != 0) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("h", hour);
            put(row, "t", e.temperature());
            put(row, "sky", e.sky());
            put(row, "pop", e.pop());
            put(row, "mm", e.precipitation());
            rows.add(row);
        }
        out.put("rows", rows);
        if (r.stale()) out.put("stale_minutes", r.ageSeconds() / 60);
        return ok(out);
    }

    /** 위치 인자(이름)가 있으면 이름으로, 없으면 요청의 좌표/도시로 조회한다. 어느 쪽도 없으면 IllegalArgumentException. */
    private Target target(String location, Context ctx) {
        if (location != null && !location.isBlank()) {
            LocationResolver.Location loc = resolver.resolve(null, null, location);
            return new Target(null, null, location, loc.name() != null ? loc.name() : location);
        }
        if (ctx.lat() != null && ctx.lon() != null) return new Target(ctx.lat(), ctx.lon(), null, "user's current position");
        if (ctx.city() != null && !ctx.city().isBlank()) return new Target(null, null, ctx.city(), ctx.city());
        throw new IllegalArgumentException("no location given: ask the user which place they mean");
    }

    private record Target(Double lat, Double lon, String city, String label) {}

    private static void put(Map<String, Object> m, String key, Object value) {
        if (value != null) m.put(key, value);
    }

    private Result ok(Map<String, Object> body) throws JsonProcessingException {
        return new Result(mapper.writeValueAsString(body), false);
    }

    private JsonNode buildDefinitions() {
        String json = """
                [
                 {"name":"search_location","description":"Find Korean place names matching a query (use when the place is ambiguous).",
                  "input_schema":{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}},
                 {"name":"get_current_weather","description":"Current temperature, rain and sky for a place. Omit location to use the user's own position.",
                  "input_schema":{"type":"object","properties":{"location":{"type":"string","description":"Korean place name, e.g. 서울 or 부산 해운대구"}}}},
                 {"name":"get_forecast","description":"Forecast for one day: min/max temperature and 3-hourly rows (t=temp C, pop=rain probability %, mm=rain). day_offset 0=today,1=tomorrow,2=day after,3.",
                  "input_schema":{"type":"object","properties":{"location":{"type":"string"},"day_offset":{"type":"integer","minimum":0,"maximum":3}}}}
                ]
                """;
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
