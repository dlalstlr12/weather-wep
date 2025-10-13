package com.example.weather.service;

import com.example.weather.dto.WeatherDtos;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class WeatherService {

    public WeatherDtos.CurrentWeatherResponse getCurrent(Double lat, Double lon, String city) {
        // TODO: KMA 연동 전까지 더미 데이터 반환
        // 추후: lat/lon -> nx,ny 변환 후 KMA 초단기/단기 예보를 호출하여 정규화
        double temp = 20.0;
        double pcp = 0.0;
        String sky = "맑음";

        if (city != null && !city.isBlank()) {
            Map<String, Double> presets = Map.of(
                    // 간단 프리셋 (임시)
                    "seoul_lat", 37.5665,
                    "seoul_lon", 126.9780,
                    "busan_lat", 35.1796,
                    "busan_lon", 129.0756
            );
            if (city.toLowerCase().contains("busan") || city.contains("부산")) {
                lat = presets.get("busan_lat");
                lon = presets.get("busan_lon");
                sky = "구름 조금";
            } else {
                lat = presets.get("seoul_lat");
                lon = presets.get("seoul_lon");
            }
        }

        if (lat != null && lon != null) {
            // 위치에 따라 더미값 살짝 변주
            temp = 18.0 + (lat % 5);
            pcp = Math.max(0.0, (lon % 3) - 1);
            sky = pcp > 0.5 ? "흐림" : sky;
        }
        return new WeatherDtos.CurrentWeatherResponse(temp, pcp, sky);
    }
}
