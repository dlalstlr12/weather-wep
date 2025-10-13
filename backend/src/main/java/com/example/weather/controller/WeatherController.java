package com.example.weather.controller;

import com.example.weather.dto.WeatherDtos;
import com.example.weather.service.WeatherService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/weather")
@RequiredArgsConstructor
public class WeatherController {

    private final WeatherService weatherService;

    @GetMapping("/current")
    public ResponseEntity<WeatherDtos.CurrentWeatherResponse> current(
            @RequestParam(value = "lat", required = false) Double lat,
            @RequestParam(value = "lon", required = false) Double lon,
            @RequestParam(value = "city", required = false) String city,
            @RequestParam(value = "nocache", required = false, defaultValue = "false") boolean nocache
    ) {
        return ResponseEntity.ok(weatherService.getCurrent(lat, lon, city, nocache));
    }

    @GetMapping("/forecast")
    public ResponseEntity<WeatherDtos.ForecastResponse> forecast(
            @RequestParam(value = "lat", required = false) Double lat,
            @RequestParam(value = "lon", required = false) Double lon,
            @RequestParam(value = "city", required = false) String city,
            @RequestParam(value = "nocache", required = false, defaultValue = "false") boolean nocache
    ) {
        return ResponseEntity.ok(weatherService.getForecast(lat, lon, city, nocache));
    }
}
