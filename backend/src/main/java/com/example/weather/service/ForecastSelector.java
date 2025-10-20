package com.example.weather.service;

import com.example.weather.dto.WeatherDtos;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class ForecastSelector {
    private ForecastSelector() {}

    public static int chooseIndex(WeatherDtos.ForecastResponse fr, String query) {
        if (fr == null || fr.items() == null || fr.items().isEmpty()) return -1;
        IntentParser.Intent intent = IntentParser.parseIntent(query);
        LocalDate baseDate = LocalDate.now(ZoneId.systemDefault()).plusDays(intent.dayOffset);
        Integer targetHour = IntentParser.targetHourForWindow(intent.window);
        int bestIdx = -1;
        long bestScore = Long.MAX_VALUE;
        for (int i = 0; i < fr.items().size(); i++) {
            var it = fr.items().get(i);
            LocalDateTime dt = parseDateTimeSafe(String.valueOf(it.dateTime()));
            if (dt == null) continue;
            if (!dt.toLocalDate().isEqual(baseDate)) continue;
            long score;
            if (targetHour != null) {
                score = Math.abs(dt.getHour() - targetHour);
            } else {
                score = Math.abs(dt.getHour() - 12);
            }
            try {
                long negPop = -Math.round(Double.parseDouble(String.valueOf(it.pop())));
                score = score * 1000 + (negPop + 1000);
            } catch (Exception ignored) { }
            if (score < bestScore) { bestScore = score; bestIdx = i; }
        }
        if (bestIdx == -1) bestIdx = 0;
        return bestIdx;
    }

    private static LocalDateTime parseDateTimeSafe(String s) {
        if (s == null) return null;
        String[] patterns = new String[]{
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm",
                "yyyy-MM-dd'T'HH:mm",
                "yyyyMMddHHmm",
                "yyyyMMddHH"
        };
        for (String p : patterns) {
            try {
                DateTimeFormatter f = DateTimeFormatter.ofPattern(p);
                return LocalDateTime.parse(s, f);
            } catch (Exception ignored) { }
        }
        try { return LocalDateTime.parse(s); } catch (Exception ignored) { }
        return null;
    }
}
