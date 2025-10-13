package com.example.weather.dto;

public class WeatherDtos {
    public record CurrentWeatherResponse(
            Double temperature,
            Double precipitation,
            String sky
    ) {}
}
