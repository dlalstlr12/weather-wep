package com.example.weather.dto;

public class WeatherDtos {
    public record CurrentWeatherResponse(
            Double temperature,
            Double precipitation,
            String sky
    ) {}

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
            java.util.List<ForecastEntry> items
    ) {}
}
