package com.example.weather.service;

import org.springframework.stereotype.Component;

/** 요청의 좌표/도시명을 조회할 좌표 하나로 정한다. 좌표가 있으면 좌표를, 없으면 도시명을 쓴다. */
@Component
public class LocationResolver {

    public record Location(double lat, double lon) {}

    public Location resolve(Double lat, Double lon, String city) {
        if (lat != null && lon != null) return new Location(lat, lon);
        if (city != null && !city.isBlank()) {
            double[] c = GeoService.geocode(city);
            if (c == null) {
                throw new IllegalArgumentException("지원하지 않는 도시입니다: " + city.trim());
            }
            return new Location(c[0], c[1]);
        }
        throw new IllegalArgumentException("위치 정보(lat, lon 또는 city)가 필요합니다.");
    }
}
