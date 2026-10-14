package com.example.weather.service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 주요 도시 이름 → 좌표. 지원 도시는 11곳뿐이며 한글/영문 이름과 "부산광역시"처럼 이름을 포함한 표기를 인식한다. */
public class GeoService {
    private GeoService() {}

    private static final Map<String, double[]> CITIES = new LinkedHashMap<>();

    static {
        put(37.5665, 126.9780, "서울", "seoul");
        put(35.1796, 129.0756, "부산", "busan");
        put(35.8714, 128.6014, "대구", "daegu");
        put(37.4563, 126.7052, "인천", "incheon");
        put(35.1595, 126.8526, "광주", "gwangju");
        put(36.3504, 127.3845, "대전", "daejeon");
        put(35.5384, 129.3114, "울산", "ulsan");
        put(37.2636, 127.0286, "수원", "suwon");
        put(35.2283, 128.6811, "창원", "changwon");
        put(37.6584, 126.8320, "고양", "goyang");
        put(37.2411, 127.1775, "용인", "yongin");
    }

    private static void put(double lat, double lon, String... names) {
        double[] coord = {lat, lon};
        for (String n : names) CITIES.put(n, coord);
    }

    /** 일치하는 도시의 {위도, 경도}. 정확히 일치하는 이름을 먼저 찾고, 없으면 이름을 포함하는 표기를 찾는다. 모르면 null */
    public static double[] geocode(String city) {
        if (city == null) return null;
        String c = city.trim().toLowerCase(Locale.ROOT);
        if (c.isEmpty()) return null;
        double[] exact = CITIES.get(c);
        if (exact != null) return exact;
        for (Map.Entry<String, double[]> e : CITIES.entrySet()) {
            if (c.contains(e.getKey())) return e.getValue();
        }
        return null;
    }
}
