package com.example.weather.dto;

public class WeatherDtos {
    /**
     * stale이면 기상청 갱신에 실패해 ageSeconds초 전에 받아 둔 값을 대신 준 것이다.
     */
    public record CurrentWeatherResponse(
            Double temperature,
            Double precipitation,
            String sky,
            boolean stale,
            long ageSeconds
    ) {
        public CurrentWeatherResponse(Double temperature, Double precipitation, String sky) {
            this(temperature, precipitation, sky, false, 0);
        }
    }

    public record ForecastEntry(
            String dateTime,
            Double temperature,
            Double precipitation,
            String sky,
            Integer pop,
            Integer reh,
            Double wsd
    ) {}

    public record ForecastResponse(
            java.util.List<ForecastEntry> items,
            boolean stale,
            long ageSeconds
    ) {
        public ForecastResponse(java.util.List<ForecastEntry> items) {
            this(items, false, 0);
        }
    }
}
