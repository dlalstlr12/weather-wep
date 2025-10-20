package com.example.weather.service;

import com.example.weather.dto.WeatherDtos;

import java.util.Locale;

public class NlgFormatter {
    private NlgFormatter() {}

    private static String nz(Object v) { return v == null ? "-" : String.valueOf(v); }

    public static String formatCurrent(WeatherDtos.CurrentWeatherResponse cw, String city) {
        if (cw == null) return null;
        String loc = city != null && !city.isBlank() ? city : "해당 지역";
        String sky = naturalSky(nz(cw.sky()));
        String temp = nz(cw.temperature());
        String pcp = nz(cw.precipitation());
        String[] templates = new String[]{
                "지금 %s%s %s이고 기온은 %s°C, 강수는 %smm입니다.",
                "%s의 현재 하늘은 %s이며 기온 %s°C, 강수 %smm입니다.",
                "현재 %s%s %s입니다. 기온 %s°C, 강수 %smm입니다."
        };
        int idx = Math.abs((loc + temp + pcp).hashCode()) % templates.length;
        StringBuilder sb;
        switch (idx) {
            case 0:
                sb = new StringBuilder(String.format(templates[0], loc, josa(loc, "은는"), sky, temp, pcp));
                break;
            case 1:
                sb = new StringBuilder(String.format(templates[1], loc, sky, temp, pcp));
                break;
            default:
                sb = new StringBuilder(String.format(templates[2], loc, josa(loc, "은는"), sky, temp, pcp));
        }
        String advice = buildAdviceCurrent(temp, pcp, sky);
        if (!advice.isBlank()) sb.append(" ").append(advice);
        return sb.toString();
    }

    public static String formatForecast(WeatherDtos.ForecastResponse fr, String city, String query, int index) {
        if (fr == null || fr.items() == null || fr.items().isEmpty()) return null;
        int idx = index;
        if (idx < 0 || idx >= fr.items().size()) idx = 0;
        var it = fr.items().get(idx);
        String loc = city != null && !city.isBlank() ? city : "해당 지역";
        String sky = naturalSky(nz(it.sky()));
        String temp = nz(it.temperature());
        String pop = nz(it.pop());
        String[] templates = new String[]{
                "예보에 따르면 %s%s %s%s 예상되고 기온은 %s°C, 강수확률은 %s%%입니다.",
                "%s의 예보는 %s%s 예상되며 기온 %s°C, 강수확률 %s%%입니다.",
                "예상되는 %s의 하늘은 %s%s, 기온 %s°C, 강수확률 %s%%입니다."
        };
        int fidx = Math.abs((loc + temp + pop).hashCode()) % templates.length;
        StringBuilder sb;
        switch (fidx) {
            case 0:
                sb = new StringBuilder(String.format(templates[0],
                        loc, josa(loc, "은는"), sky, josa(sky, "이가"), temp, pop));
                break;
            case 1:
                sb = new StringBuilder(String.format(templates[1],
                        loc, sky, josa(sky, "이가"), temp, pop));
                break;
            default:
                sb = new StringBuilder(String.format(templates[2],
                        loc, sky, josa(sky, "이가"), temp, pop));
        }
        String advice = buildAdviceForecast(temp, pop, sky);
        if (!advice.isBlank()) sb.append(" ").append(advice);
        return sb.toString();
    }

    public static String naturalSky(String skyRaw) {
        String s = skyRaw.toLowerCase(java.util.Locale.ROOT).trim();
        if (s.isEmpty() || s.equals("-")) return "맑음";
        if (s.contains("clear") || s.contains("sunny") || s.contains("맑")) return "맑음";
        if (s.contains("partly") || s.contains("구름") || s.contains("cloud")) return "구름 많음";
        if (s.contains("overcast") || s.contains("흐림") || s.equals("흐림")) return "흐림";
        if (s.contains("rain") || s.contains("비")) return "비";
        if (s.contains("snow") || s.contains("눈")) return "눈";
        return skyRaw;
    }

    private static String josa(String token, String type) {
        if (token == null || token.isBlank()) return "";
        char last = token.charAt(token.length() - 1);
        boolean hasBatchim = (last >= 0xAC00 && last <= 0xD7A3) && (((last - 0xAC00) % 28) != 0);
        switch (type) {
            case "은는": return hasBatchim ? "은" : "는";
            case "이가": return hasBatchim ? "이" : "가";
            case "을를": return hasBatchim ? "을" : "를";
            default: return "";
        }
    }

    private static String buildAdviceCurrent(String tempStr, String pcpStr, String sky) {
        double temp = parseDoubleSafe(tempStr, Double.NaN);
        double pcp = parseDoubleSafe(pcpStr, 0.0);
        if (pcp > 0.0 || (sky != null && sky.contains("비"))) return "우산을 챙기면 좋아요.";
        if (!Double.isNaN(temp)) {
            if (temp <= 0) return "두꺼운 외투와 장갑을 준비하세요.";
            if (temp <= 5) return "겉옷을 꼭 챙기세요.";
            if (temp >= 30) return "더위에 유의하고 수분을 충분히 섭취하세요.";
            if (temp >= 26) return "가벼운 옷차림이 좋아요.";
        }
        return "";
    }

    private static String buildAdviceForecast(String tempStr, String popStr, String sky) {
        double temp = parseDoubleSafe(tempStr, Double.NaN);
        double pop = parseDoubleSafe(popStr, Double.NaN);
        if (!Double.isNaN(pop)) {
            if (pop >= 60) return "비 가능성이 높으니 우산을 준비하세요.";
            if (pop >= 30) return "소나기에 대비해 가벼운 우산을 고려하세요.";
        }
        if (!Double.isNaN(temp)) {
            if (temp <= 0) return "매우 춥겠습니다. 보온에 유의하세요.";
            if (temp <= 5) return "쌀쌀하니 겉옷을 챙기세요.";
            if (temp >= 30) return "더위에 유의하고 수분 섭취를 잊지 마세요.";
            if (temp >= 26) return "덥겠습니다. 가벼운 옷차림이 좋아요.";
        }
        if (sky != null && sky.contains("눈")) return "눈길 안전에 유의하세요.";
        return "";
    }

    private static double parseDoubleSafe(String s, double def) {
        if (s == null) return def;
        try { return Double.parseDouble(s); } catch (Exception e) { return def; }
    }
}
