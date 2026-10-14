package com.example.weather.service;

import com.example.weather.util.KmaGridConverter;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 기상청 조회 진입점. 격자 변환 → 기준 시각 계산 → 캐시 → HTTP 호출 → 응답 파싱을 순서대로 엮는다.
 * 각 단계는 KmaGridConverter, KmaBaseTime, KmaCache, KmaApiClient, KmaResponseParser가 맡는다.
 */
@Component
public class KmaClient {

    private static final String ULTRA_ENDPOINT = "/getUltraSrtNcst";
    private static final String VILAGE_ENDPOINT = "/getVilageFcst";

    private final String serviceKey;
    private final String provider; // data | hub
    private final KmaApiClient api;
    private final KmaBaseTime baseTime;
    private final KmaCache cache;

    @Autowired
    public KmaClient(
            @Value("${kma.service-key:}") String serviceKey,
            @Value("${kma.provider:data}") String provider,
            @Value("${kma.data.base-url:https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0}") String dataBaseUrl,
            @Value("${kma.hub.base-url:https://apihub.kma.go.kr}") String hubBaseUrl,
            @Value("${kma.hub.auth.mode:header}") String hubAuthMode,
            @Value("${kma.hub.auth.header-name:x-api-key}") String hubHeaderName,
            @Value("${kma.hub.auth.query-name:serviceKey}") String hubQueryName
    ) {
        this(serviceKey, provider, dataBaseUrl, hubBaseUrl, hubAuthMode, hubHeaderName, hubQueryName,
                Clock.system(ZoneId.of("Asia/Seoul")));
    }

    KmaClient(String serviceKey, String provider, String dataBaseUrl, String hubBaseUrl,
              String hubAuthMode, String hubHeaderName, String hubQueryName, Clock clock) {
        this.serviceKey = serviceKey;
        this.provider = provider;
        this.api = new KmaApiClient(serviceKey, provider, dataBaseUrl, hubBaseUrl, hubAuthMode, hubHeaderName, hubQueryName);
        this.baseTime = new KmaBaseTime(clock);
        this.cache = new KmaCache(clock);
    }

    public List<Map<String, String>> getVilageForecast(double lat, double lon, boolean nocache) {
        requireServiceKey();
        KmaGridConverter.Grid grid = KmaGridConverter.toGrid(lat, lon);
        KmaBaseTime.Base base = baseTime.vilage();
        return cache.get(cacheKey("vilage", grid, base), nocache,
                () -> KmaResponseParser.vilageForecast(api.get(VILAGE_ENDPOINT, base, grid)));
    }

    public Map<String, String> getUltraNowcast(double lat, double lon, boolean nocache) {
        requireServiceKey();
        KmaGridConverter.Grid grid = KmaGridConverter.toGrid(lat, lon);
        // 허브는 초단기실황 대신 단기예보 응답을 쓴다
        KmaBaseTime.Base base = api.isHub() ? baseTime.vilage() : baseTime.ultra();
        String endpoint = api.isHub() ? VILAGE_ENDPOINT : ULTRA_ENDPOINT;
        return cache.get(cacheKey("ultra", grid, base), nocache,
                () -> KmaResponseParser.ultraNowcast(api.get(endpoint, base, grid)));
    }

    private void requireServiceKey() {
        if (serviceKey == null || serviceKey.isBlank()) {
            throw new IllegalStateException("KMA 서비스키가 설정되어 있지 않습니다. KMA_SERVICE_KEY 환경변수를 설정하세요.");
        }
    }

    private String cacheKey(String kind, KmaGridConverter.Grid grid, KmaBaseTime.Base base) {
        return String.format(Locale.ROOT, "%s|%s|%d|%d|%s|%s",
                provider.toLowerCase(Locale.ROOT), kind, grid.nx(), grid.ny(), base.date(), base.time());
    }

    public static String skyCodeToText(String code) {
        // SKY: 1 맑음, 3 구름많음, 4 흐림
        if (code == null) return "-";
        return switch (code) {
            case "1" -> "맑음";
            case "3" -> "구름많음";
            case "4" -> "흐림";
            default -> "-";
        };
    }
}
