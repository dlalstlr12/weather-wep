package com.example.weather.service;

import com.example.weather.util.KmaGridConverter;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 기상청 조회 진입점. 격자 변환 → 기준 시각 계산 → 캐시 → (재시도 + 서킷 브레이커) HTTP 호출 → 응답 파싱을 엮는다.
 * 각 단계는 KmaGridConverter, KmaBaseTime, KmaCache, KmaResilience/KmaApiClient, KmaResponseParser가 맡는다.
 */
@Component
public class KmaClient {

    private static final String ULTRA_ENDPOINT = "/getUltraSrtNcst";
    private static final String VILAGE_ENDPOINT = "/getVilageFcst";

    private final String serviceKey;
    private final String provider;
    private final KmaApiClient api;
    private final KmaResilience resilience;
    private final KmaBaseTime baseTime;
    private final KmaCache cache;

    @Autowired
    public KmaClient(KmaProperties props) {
        this(props, Clock.system(ZoneId.of("Asia/Seoul")));
    }

    KmaClient(KmaProperties props, Clock clock) {
        this.serviceKey = props.serviceKey();
        this.provider = props.provider();
        this.api = new KmaApiClient(props);
        this.resilience = new KmaResilience(props.resilience());
        this.baseTime = new KmaBaseTime(clock);
        this.cache = new KmaCache(clock, props.cache());
    }

    public KmaResult<List<Map<String, String>>> getVilageForecast(double lat, double lon, boolean nocache) {
        return getVilageForecast(KmaGridConverter.toGrid(lat, lon), nocache);
    }

    public KmaResult<List<Map<String, String>>> getVilageForecast(KmaGridConverter.Grid grid, boolean nocache) {
        requireServiceKey();
        KmaBaseTime.Base base = baseTime.vilage();
        return cache.get(cacheKey("vilage", grid, base), nocache,
                () -> loadWithPreviousSlotFallback(base, KmaBaseTime::previousVilage,
                        b -> call(VILAGE_ENDPOINT, b, grid, KmaResponseParser::vilageForecast)));
    }

    public KmaResult<Map<String, String>> getUltraNowcast(double lat, double lon, boolean nocache) {
        return getUltraNowcast(KmaGridConverter.toGrid(lat, lon), nocache);
    }

    public KmaResult<Map<String, String>> getUltraNowcast(KmaGridConverter.Grid grid, boolean nocache) {
        requireServiceKey();
        // 허브는 초단기실황 대신 단기예보 응답을 쓴다
        boolean hub = api.isHub();
        KmaBaseTime.Base base = hub ? baseTime.vilage() : baseTime.ultra();
        String endpoint = hub ? VILAGE_ENDPOINT : ULTRA_ENDPOINT;
        UnaryOperator<KmaBaseTime.Base> previous = hub ? KmaBaseTime::previousVilage : KmaBaseTime::previousUltra;
        return cache.get(cacheKey("ultra", grid, base), nocache,
                () -> loadWithPreviousSlotFallback(base, previous,
                        b -> call(endpoint, b, grid, KmaResponseParser::ultraNowcast)));
    }

    /**
     * HTTP 호출과 응답 파싱을 재시도·서킷 브레이커로 감싼다.
     * 파싱까지 안에 넣어야 resultCode 오류(게이트웨이 타임아웃 05, 호출 한도 22 등)도 재시도·차단 판단에 반영된다.
     */
    private <T> T call(String endpoint, KmaBaseTime.Base base, KmaGridConverter.Grid grid,
                       Function<Map<?, ?>, T> parser) {
        return resilience.call(() -> parser.apply(api.get(endpoint, base, grid)));
    }

    /** 현재 기준 시각의 자료가 아직 없으면(발표 직후 등) 바로 앞 슬롯으로 한 번 더 조회한다. */
    private <T> T loadWithPreviousSlotFallback(KmaBaseTime.Base base, UnaryOperator<KmaBaseTime.Base> previous,
                                               Function<KmaBaseTime.Base, T> fetch) {
        try {
            return fetch.apply(base);
        } catch (KmaApiException e) {
            if (e.kind() != KmaApiException.Kind.NO_DATA) throw e;
            return fetch.apply(previous.apply(base));
        }
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
