package com.example.weather.service;

import com.example.weather.util.KmaGridConverter;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 기상청 HTTP 호출. 공공데이터포털(data)과 API Hub(hub)의 URL·인증 방식 차이를 이 클래스 안에 가둔다. */
@Slf4j
final class KmaApiClient {

    private final RestClient http;
    private final String serviceKey;
    private final boolean hub;
    private final String dataBaseUrl;
    private final String hubBaseUrl;
    private final String hubAuthMode; // header | query
    private final String hubHeaderName;
    private final String hubQueryName;

    KmaApiClient(KmaProperties p) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(p.http().connectTimeoutMs());
        factory.setReadTimeout(p.http().readTimeoutMs());
        this.http = RestClient.builder().requestFactory(factory).build();
        this.serviceKey = p.serviceKey();
        this.hub = "hub".equalsIgnoreCase(p.provider());
        this.dataBaseUrl = p.data().baseUrl();
        this.hubBaseUrl = p.hub().baseUrl();
        this.hubAuthMode = p.hub().auth().mode();
        this.hubHeaderName = p.hub().auth().headerName();
        this.hubQueryName = p.hub().auth().queryName();
    }

    boolean isHub() {
        return hub;
    }

    Map<?, ?> get(String endpoint, KmaBaseTime.Base base, KmaGridConverter.Grid grid) {
        String query = String.format(Locale.ROOT,
                "pageNo=1&numOfRows=1000&dataType=JSON&base_date=%s&base_time=%s&nx=%d&ny=%d",
                base.date(), base.time(), grid.nx(), grid.ny());

        String url;
        String headerName = null;
        if (hub) {
            url = hubBaseUrl + endpoint + "?" + query;
            if ("header".equalsIgnoreCase(hubAuthMode)) {
                headerName = hubHeaderName;
            } else {
                url += "&" + hubQueryName + "=" + encodeIfNeeded(serviceKey);
            }
        } else {
            url = dataBaseUrl + endpoint + "?serviceKey=" + encodeIfNeeded(serviceKey) + "&" + query;
        }

        try {
            RestClient.RequestHeadersSpec<?> request = http.get().uri(URI.create(url));
            if (headerName != null) request = request.header(headerName, serviceKey);
            return request.retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        // 메시지·로그에 URL을 넣지 않는다 (쿼리에 서비스키가 들어 있음)
                        log.warn("KMA HTTP {} endpoint={}", res.getStatusCode().value(), endpoint);
                        throw new KmaApiException(KmaApiException.Kind.HTTP_ERROR,
                                "KMA 호출 실패: HTTP " + res.getStatusCode() + " (endpoint=" + endpoint + ")",
                                null, res.getStatusCode().value(), null);
                    })
                    .body(Map.class);
        } catch (ResourceAccessException e) {
            throw new KmaApiException(KmaApiException.Kind.UNREACHABLE, "KMA 연결 실패 (endpoint=" + endpoint + ")", null, e);
        } catch (RestClientException e) {
            throw new KmaApiException(KmaApiException.Kind.PARSE_ERROR, "KMA 응답 파싱 실패", null, e);
        }
    }

    private static String encodeIfNeeded(String key) {
        if (key == null) return null;
        if (key.contains("%") || key.contains("+")) return key; // 이미 인코딩된 키로 판단
        return URLEncoder.encode(key, StandardCharsets.UTF_8);
    }
}
