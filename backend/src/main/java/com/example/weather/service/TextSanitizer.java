package com.example.weather.service;

public class TextSanitizer {
    private TextSanitizer() {}

    public static String sanitize(String s) {
        if (s == null) return null;
        String out = s.replace('\r', ' ').replace('\t', ' ');
        out = out.replaceAll("[\u0000-\u001F]", " ");
        out = out.replaceAll("[=>]{3,}", "");
        out = out.replaceAll("={2,}", "=");
        out = out.replaceAll("\\s+", " ").trim();
        return out;
    }
}
