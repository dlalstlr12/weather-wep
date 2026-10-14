package com.example.weather.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 기상청 JSON 응답을 서비스에서 쓰는 형태로 바꾼다. */
final class KmaResponseParser {
    private KmaResponseParser() {}

    /** 초단기실황: category → 값 (obsrValue, 없으면 fcstValue) */
    static Map<String, String> ultraNowcast(Map<?, ?> body) {
        Map<String, String> result = new HashMap<>();
        for (Map<?, ?> item : items(body)) {
            Object v = item.get("obsrValue");
            if (v == null) v = item.get("fcstValue");
            result.put(String.valueOf(item.get("category")), v == null ? null : String.valueOf(v));
        }
        return result;
    }

    /** 단기예보: 예보 시각(fcstDate+fcstTime)별로 category → 값을 묶고 dateTime 키를 추가한다 */
    static List<Map<String, String>> vilageForecast(Map<?, ?> body) {
        Map<String, Map<String, String>> grouped = new LinkedHashMap<>();
        for (Map<?, ?> item : items(body)) {
            String dateTime = String.valueOf(item.get("fcstDate")) + item.get("fcstTime");
            grouped.computeIfAbsent(dateTime, k -> new HashMap<>())
                    .put(String.valueOf(item.get("category")), String.valueOf(item.get("fcstValue")));
        }
        List<Map<String, String>> out = new ArrayList<>();
        for (var e : grouped.entrySet()) {
            Map<String, String> m = new HashMap<>(e.getValue());
            m.put("dateTime", e.getKey());
            out.add(m);
        }
        return out;
    }

    private static List<Map<?, ?>> items(Map<?, ?> body) {
        try {
            Map<?, ?> response = (Map<?, ?>) body.get("response");
            Map<?, ?> header = (Map<?, ?>) response.get("header");
            Object resultCode = header.get("resultCode");
            if (resultCode != null && !"00".equals(resultCode.toString())) {
                throw new KmaApiException(KmaApiException.Kind.RESULT_CODE,
                        "KMA 오류: code=" + resultCode + ", msg=" + header.get("resultMsg"), resultCode.toString(), null);
            }
            Map<?, ?> items = (Map<?, ?>) ((Map<?, ?>) response.get("body")).get("items");
            List<?> list = (List<?>) items.get("item");
            List<Map<?, ?>> out = new ArrayList<>();
            if (list != null) {
                for (Object o : list) out.add((Map<?, ?>) o);
            }
            return out;
        } catch (KmaApiException e) {
            throw e;
        } catch (Exception e) {
            throw new KmaApiException(KmaApiException.Kind.PARSE_ERROR, "KMA 응답 파싱 실패", null, e);
        }
    }
}
