package com.example.weather.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.weather.dto.WeatherDtos;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ForecastSelector의 현재 동작을 고정하는 특성화 테스트. 날짜는 실행 시점 기준으로 만든다. */
class ForecastSelectorTest {

    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static String dt(int plusDays, int hour) {
        LocalDate d = LocalDate.now(ZoneId.systemDefault()).plusDays(plusDays);
        return d.format(F) + String.format("%02d00", hour);
    }

    private static WeatherDtos.ForecastEntry entry(String dateTime, int pop) {
        return new WeatherDtos.ForecastEntry(dateTime, 20.0, 0.0, "맑음", pop, 50, 1.0);
    }

    private static WeatherDtos.ForecastResponse resp(WeatherDtos.ForecastEntry... items) {
        return new WeatherDtos.ForecastResponse(new ArrayList<>(List.of(items)));
    }

    @Test
    void emptyOrNullReturnsMinusOne() {
        assertEquals(-1, ForecastSelector.chooseIndex(null, "오늘"));
        assertEquals(-1, ForecastSelector.chooseIndex(new WeatherDtos.ForecastResponse(List.of()), "오늘"));
    }

    @Test
    void noWindowPicksClosestToNoon() {
        var fr = resp(entry(dt(0, 9), 10), entry(dt(0, 12), 10), entry(dt(0, 18), 10));
        assertEquals(1, ForecastSelector.chooseIndex(fr, "날씨"));
    }

    @Test
    void afternoonPicksClosestTo15() {
        var fr = resp(entry(dt(0, 9), 10), entry(dt(0, 14), 10), entry(dt(0, 18), 10));
        assertEquals(1, ForecastSelector.chooseIndex(fr, "오후 날씨"));
    }

    @Test
    void tomorrowUsesTomorrowEntries() {
        var fr = resp(entry(dt(0, 12), 10), entry(dt(1, 12), 10));
        assertEquals(1, ForecastSelector.chooseIndex(fr, "내일 날씨"));
    }

    @Test
    void whenTargetDayMissingFallsBackToFirstItem() {
        var fr = resp(entry(dt(0, 12), 10), entry(dt(0, 15), 10));
        assertEquals(0, ForecastSelector.chooseIndex(fr, "모레 날씨"));
    }
}
