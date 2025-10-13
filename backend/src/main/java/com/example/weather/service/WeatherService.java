package com.example.weather.service;

import com.example.weather.dto.WeatherDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WeatherService {

    private final KmaClient kmaClient;

    public WeatherDtos.CurrentWeatherResponse getCurrent(Double lat, Double lon, String city) {
        // 간단 프리셋(임시): 도시명이 오면 좌표 대체
        if (city != null && !city.isBlank()) {
            String c = city.toLowerCase(Locale.ROOT);
            if (c.contains("busan") || city.contains("부산")) {
                lat = 35.1796; lon = 129.0756;
            } else { // default: 서울
                lat = 37.5665; lon = 126.9780;
            }
        }

        if (lat == null || lon == null) {
            // 좌표가 없다면 의미있는 응답 불가
            throw new IllegalArgumentException("위치 정보(lat, lon)가 필요합니다.");
        }

        Map<String, String> now = kmaClient.getUltraNowcast(lat, lon);

        // 실황: T1H/RN1, 예보: TMP/PCP
        String t = firstNonNull(now.get("T1H"), now.get("TMP"));
        String p = firstNonNull(now.get("RN1"), now.get("PCP"));
        Double temperature = parseDoubleSafe(t);
        Double precipitation = normalizePrecipitation(p);
        String sky = KmaClient.skyCodeToText(now.get("SKY"));

        return new WeatherDtos.CurrentWeatherResponse(temperature, precipitation, sky);
    }

    public WeatherDtos.ForecastResponse getForecast(Double lat, Double lon, String city) {
        if (city != null && !city.isBlank()) {
            String c = city.toLowerCase(Locale.ROOT);
            if (c.contains("busan") || city.contains("부산")) {
                lat = 35.1796; lon = 129.0756;
            } else {
                lat = 37.5665; lon = 126.9780;
            }
        }
        if (lat == null || lon == null) {
            throw new IllegalArgumentException("위치 정보(lat, lon)가 필요합니다.");
        }
        var list = kmaClient.getVilageForecast(lat, lon);
        java.util.List<WeatherDtos.ForecastEntry> items = new java.util.ArrayList<>();
        for (var m : list) {
            String dt = m.get("dateTime");
            String t = firstNonNull(m.get("TMP"), m.get("T1H"));
            String p = firstNonNull(m.get("PCP"), m.get("RN1"));
            String skyCode = m.get("SKY");
            String popStr = m.get("POP");
            String rehStr = m.get("REH");
            String wsdStr = m.get("WSD");
            Double temperature = parseDoubleSafe(t);
            Double precipitation = normalizePrecipitation(p);
            String sky = KmaClient.skyCodeToText(skyCode);
            Integer pop = parseIntSafe(popStr);
            Integer reh = parseIntSafe(rehStr);
            Double wsd = parseDoubleSafe(wsdStr);
            items.add(new WeatherDtos.ForecastEntry(dt, temperature, precipitation, sky, pop, reh, wsd));
        }
        return new WeatherDtos.ForecastResponse(items);
    }

    private static Double parseDoubleSafe(String v) {
        if (v == null || v.isBlank() || "-".equals(v)) return null;
        try { return Double.parseDouble(v); } catch (Exception e) { return null; }
    }

    private static String firstNonNull(String a, String b) {
        return (a != null && !a.isBlank() && !"-".equals(a)) ? a : b;
    }

    private static Double normalizePrecipitation(String p) {
        if (p == null || p.isBlank() || "-".equals(p)) return null;
        String s = p.trim();
        if (s.contains("없음")) return 0.0;
        if (s.contains("미만")) return 0.0; // 보수적으로 0 처리
        s = s.replace("mm", "").trim();
        try { return Double.parseDouble(s); } catch (Exception e) { return null; }
    }

    private static Integer parseIntSafe(String v) {
        if (v == null || v.isBlank() || "-".equals(v)) return null;
        try { return Integer.parseInt(v); } catch (Exception e) { return null; }
    }
}
