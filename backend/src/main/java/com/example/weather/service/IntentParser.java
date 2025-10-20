package com.example.weather.service;

import java.util.Locale;

public class IntentParser {
    private IntentParser() {}

    public static String inferCity(String explicitCity, String lastUser) {
        if (explicitCity != null && !explicitCity.isBlank()) return explicitCity;
        if (lastUser == null) return null;
        String t = lastUser.toLowerCase(Locale.ROOT);
        if (t.contains("서울") || t.contains("seoul")) return "서울";
        if (t.contains("부산") || t.contains("busan")) return "부산";
        if (t.contains("인천") || t.contains("incheon")) return "인천";
        if (t.contains("대구") || t.contains("daegu")) return "대구";
        if (t.contains("대전") || t.contains("daejeon")) return "대전";
        if (t.contains("광주") || t.contains("gwangju")) return "광주";
        if (t.contains("울산") || t.contains("ulsan")) return "울산";
        if (t.contains("수원") || t.contains("suwon")) return "수원";
        if (t.contains("창원") || t.contains("changwon")) return "창원";
        if (t.contains("고양") || t.contains("goyang")) return "고양";
        if (t.contains("용인") || t.contains("yongin")) return "용인";
        return null;
    }

    public static boolean containsAny(String text, String... keys) {
        if (text == null) return false;
        String t = text.toLowerCase(Locale.ROOT);
        for (String k : keys) {
            if (t.contains(k.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    public static class Intent {
        public int dayOffset; // 0 today, 1 tomorrow, 2 day after
        public String window; // morning/afternoon/evening/night/any
    }

    public static Intent parseIntent(String text) {
        Intent in = new Intent();
        in.dayOffset = 0; in.window = "any";
        if (text == null) return in;
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains("모레")) in.dayOffset = 2;
        else if (t.contains("내일") || t.contains("tomorrow")) in.dayOffset = 1;
        else if (t.contains("주간") || t.contains("이번 주") || t.contains("한 주")) in.dayOffset = 1; // weekly -> start from tomorrow
        if (t.contains("오전") || t.contains("morning")) in.window = "morning";
        else if (t.contains("오후") || t.contains("afternoon")) in.window = "afternoon";
        else if (t.contains("저녁") || t.contains("evening")) in.window = "evening";
        else if (t.contains("밤") || t.contains("night")) in.window = "night";
        return in;
    }

    public static Integer targetHourForWindow(String window) {
        if (window == null) return null;
        switch (window) {
            case "morning": return 9;
            case "afternoon": return 15;
            case "evening": return 19;
            case "night": return 22;
            default: return null;
        }
    }
}
