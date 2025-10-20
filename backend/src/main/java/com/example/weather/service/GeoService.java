package com.example.weather.service;

public class GeoService {
    private GeoService() {}

    public static double[] geocode(String city) {
        if (city == null) return null;
        String c = city.trim().toLowerCase(java.util.Locale.ROOT);
        switch (c) {
            case "서울": case "seoul": return new double[]{37.5665, 126.9780};
            case "부산": case "busan": return new double[]{35.1796, 129.0756};
            case "대구": case "daegu": return new double[]{35.8714, 128.6014};
            case "인천": case "incheon": return new double[]{37.4563, 126.7052};
            case "광주": case "gwangju": return new double[]{35.1595, 126.8526};
            case "대전": case "daejeon": return new double[]{36.3504, 127.3845};
            case "울산": case "ulsan": return new double[]{35.5384, 129.3114};
            case "수원": case "suwon": return new double[]{37.2636, 127.0286};
            case "창원": case "changwon": return new double[]{35.2283, 128.6811};
            case "고양": case "goyang": return new double[]{37.6584, 126.8320};
            case "용인": case "yongin": return new double[]{37.2411, 127.1775};
            default: return null;
        }
    }
}
