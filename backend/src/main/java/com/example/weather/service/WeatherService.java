package com.example.weather.service;

import com.example.weather.dto.WeatherDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class WeatherService {

    private final KmaClient kmaClient;
    private final LocationResolver locationResolver;

    public WeatherDtos.CurrentWeatherResponse getCurrent(Double lat, Double lon, String city, boolean nocache) {
        LocationResolver.Location loc = locationResolver.resolve(lat, lon, city);
        Map<String, String> now = kmaClient.getUltraNowcast(loc.lat(), loc.lon(), nocache);

        // 실황: T1H/RN1, 예보: TMP/PCP
        String t = firstNonNull(now.get("T1H"), now.get("TMP"));
        String p = firstNonNull(now.get("RN1"), now.get("PCP"));
        return new WeatherDtos.CurrentWeatherResponse(
                parseDoubleSafe(t), normalizePrecipitation(p), KmaClient.skyCodeToText(now.get("SKY")));
    }

    public WeatherDtos.ForecastResponse getForecast(Double lat, Double lon, String city, boolean nocache) {
        LocationResolver.Location loc = locationResolver.resolve(lat, lon, city);
        var list = kmaClient.getVilageForecast(loc.lat(), loc.lon(), nocache);
        java.util.List<WeatherDtos.ForecastEntry> items = new java.util.ArrayList<>();
        for (var m : list) {
            String t = firstNonNull(m.get("TMP"), m.get("T1H"));
            String p = firstNonNull(m.get("PCP"), m.get("RN1"));
            items.add(new WeatherDtos.ForecastEntry(
                    m.get("dateTime"),
                    parseDoubleSafe(t),
                    normalizePrecipitation(p),
                    KmaClient.skyCodeToText(m.get("SKY")),
                    parseIntSafe(m.get("POP")),
                    parseIntSafe(m.get("REH")),
                    parseDoubleSafe(m.get("WSD"))));
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
