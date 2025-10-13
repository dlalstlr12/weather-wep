package com.example.weather.service;

import com.example.weather.util.KmaGridConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class KmaClient {

    private final RestClient http;
    private final String serviceKey;
    private final String provider; // data | hub
    private final String dataBaseUrl;
    private final String hubBaseUrl;
    private final String hubUltraEndpoint;
    private final String hubAuthMode; // header | query
    private final String hubHeaderName;
    private final String hubQueryName;

    public KmaClient(
            @Value("${kma.service-key:}") String serviceKey,
            @Value("${kma.provider:data}") String provider,
            @Value("${kma.data.base-url:https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0}") String dataBaseUrl,
            @Value("${kma.hub.base-url:https://apihub.kma.go.kr}") String hubBaseUrl,
            @Value("${kma.hub.endpoints.ultra-nowcast:/vilage/ultra-srt-ncst}") String hubUltraEndpoint,
            @Value("${kma.hub.auth.mode:header}") String hubAuthMode,
            @Value("${kma.hub.auth.header-name:x-api-key}") String hubHeaderName,
            @Value("${kma.hub.auth.query-name:serviceKey}") String hubQueryName
    ) {
        this.http = RestClient.create();
        this.serviceKey = serviceKey;
        this.provider = provider;
        this.dataBaseUrl = dataBaseUrl;
        this.hubBaseUrl = hubBaseUrl;
        this.hubUltraEndpoint = hubUltraEndpoint;
        this.hubAuthMode = hubAuthMode;
        this.hubHeaderName = hubHeaderName;
        this.hubQueryName = hubQueryName;
    }

    public Map<String, String> getUltraNowcast(double lat, double lon) {
        if (serviceKey == null || serviceKey.isBlank()) {
            throw new IllegalStateException("KMA 서비스키가 설정되어 있지 않습니다. KMA_SERVICE_KEY 환경변수를 설정하세요.");
        }
        KmaGridConverter.Grid grid = KmaGridConverter.toGrid(lat, lon);
        Base base = "hub".equalsIgnoreCase(provider) ? computeVilageBase() : computeUltraBase();

        Map<String, Object> body;
        String url;

        if ("hub".equalsIgnoreCase(provider)) {
            // Hub: 일반적으로 key를 헤더(x-api-key)로 전달. 필요 시 query로도 지원
            String query = String.format(Locale.ROOT,
                    "?pageNo=1&numOfRows=1000&dataType=JSON&base_date=%s&base_time=%s&nx=%d&ny=%d",
                    base.date, base.time, grid.nx(), grid.ny());
            // Hub에서는 단기예보(getVilageFcst)를 사용
            url = hubBaseUrl + "/getVilageFcst" + query;

            if ("header".equalsIgnoreCase(hubAuthMode)) {
                final String requestUrl = url;
                body = http.get()
                        .uri(URI.create(requestUrl))
                        .header(hubHeaderName, serviceKey)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, (r, res) -> {
                            throw new RuntimeException("KMA(Hub) 호출 실패: HTTP " + res.getStatusCode() + " url=" + requestUrl);
                        })
                        .body(Map.class);
            } else { // query
                String sep = url.contains("?") ? "&" : "?";
                url = url + sep + hubQueryName + "=" + encodeIfNeeded(serviceKey);
                final String requestUrl = url;
                body = http.get()
                        .uri(URI.create(requestUrl))
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, (r, res) -> {
                            throw new RuntimeException("KMA(Hub) 호출 실패: HTTP " + res.getStatusCode() + " url=" + requestUrl);
                        })
                        .body(Map.class);
            }

        } else {
            // data.go.kr
            String key = encodeIfNeeded(serviceKey);
            url = String.format(Locale.ROOT,
                    "%s/getUltraSrtNcst?serviceKey=%s&pageNo=1&numOfRows=1000&dataType=JSON&base_date=%s&base_time=%s&nx=%d&ny=%d",
                    dataBaseUrl, key, base.date, base.time, grid.nx(), grid.ny());

            final String requestUrl = url;
            body = http.get()
                    .uri(URI.create(requestUrl))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new RuntimeException("KMA 호출 실패: HTTP " + res.getStatusCode() + " url=" + requestUrl);
                    })
                    .body(Map.class);
        }

        Map<String, String> result = new HashMap<>();
        try {
            Map<?, ?> response = (Map<?, ?>) body.get("response");
            Map<?, ?> header = (Map<?, ?>) response.get("header");
            Object resultCode = header.get("resultCode");
            if (resultCode != null && !"00".equals(resultCode.toString())) {
                throw new RuntimeException("KMA 오류: code=" + resultCode + ", msg=" + header.get("resultMsg"));
            }
            Map<?, ?> bodyObj = (Map<?, ?>) response.get("body");
            Map<?, ?> items = (Map<?, ?>) bodyObj.get("items");
            List<Map<String, Object>> list = (List<Map<String, Object>>) items.get("item");
            if (list != null) {
                for (Map<String, Object> item : list) {
                    String category = String.valueOf(item.get("category"));
                    Object v = item.get("obsrValue");
                    if (v == null) v = item.get("fcstValue");
                    String value = v == null ? null : String.valueOf(v);
                    result.put(category, value);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("KMA 응답 파싱 실패", e);
        }
        return result;
    }

    private static String encodeIfNeeded(String key) {
        if (key == null) return null;
        if (key.contains("%") || key.contains("+")) return key; // 이미 인코딩된 키로 판단
        return URLEncoder.encode(key, StandardCharsets.UTF_8);
    }

    public static String skyCodeToText(String code) {
        // SKY: 1 맑음, 3 구름많음, 4 흐림
        return switch (code) {
            case "1" -> "맑음";
            case "3" -> "구름많음";
            case "4" -> "흐림";
            default -> "-";
        };
    }

    private record Base(String date, String time) {}

    private static Base computeUltraBase() {
        // 초단기실황 기준시간: 매시각 40분 이후에 최신자료 제공 → 분이 40 미만이면 이전시각 30분, 아니면 해당시각 30분 사용
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        int minute = now.getMinute();
        LocalDateTime base = now;
        if (minute < 40) {
            base = now.minusHours(1);
        }
        String baseDate = base.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String baseTime = base.format(DateTimeFormatter.ofPattern("HH")) + "30"; // HH30
        return new Base(baseDate, baseTime);
    }

    private static Base computeVilageBase() {
        // 단기예보 기준시간: 02,05,08,11,14,17,20,23시 정시(HH00)
        int[] hours = {2,5,8,11,14,17,20,23};
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        LocalDateTime candidate = now.withMinute(0).withSecond(0).withNano(0);
        // 현재 시간이 목록보다 이르면 직전 슬롯으로
        int h = now.getHour();
        int chosen = -1;
        for (int i = hours.length - 1; i >= 0; i--) {
            if (h >= hours[i]) { chosen = hours[i]; break; }
        }
        if (chosen == -1) { // 00~01시는 전날 23시로
            candidate = candidate.minusDays(1).withHour(23);
        } else {
            candidate = candidate.withHour(chosen);
        }
        String baseDate = candidate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String baseTime = candidate.format(DateTimeFormatter.ofPattern("HH")) + "00"; // HH00
        return new Base(baseDate, baseTime);
    }
}
