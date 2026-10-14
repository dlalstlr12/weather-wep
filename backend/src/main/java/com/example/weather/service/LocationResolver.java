package com.example.weather.service;

import com.example.weather.location.LocationRepository;
import com.example.weather.location.Place;
import com.example.weather.util.KmaGridConverter;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 요청의 좌표/도시명을 조회할 위치 하나로 정한다.
 * 좌표가 있으면 좌표를 쓰고, 없으면 위치 테이블에서 이름으로 찾는다. 위치 테이블에서 찾지 못하면 주요 도시 11곳(GeoService)을 쓴다.
 */
@Component
public class LocationResolver {

    /** nx, ny는 위치 테이블에서 찾은 기상청 공식 격자. 좌표로 요청한 경우에는 null이고 좌표에서 계산한다. */
    public record Location(double lat, double lon, Integer nx, Integer ny, String name) {
        public Location(double lat, double lon) {
            this(lat, lon, null, null, null);
        }

        public KmaGridConverter.Grid grid() {
            return nx != null && ny != null ? new KmaGridConverter.Grid(nx, ny) : KmaGridConverter.toGrid(lat, lon);
        }
    }

    private final LocationRepository locations;

    public LocationResolver(LocationRepository locations) {
        this.locations = locations;
    }

    public Location resolve(Double lat, Double lon, String city) {
        if (lat != null && lon != null) return new Location(lat, lon);
        if (city != null && !city.isBlank()) {
            Optional<Place> place = locations.findBestMatch(city);
            if (place.isPresent()) {
                Place p = place.get();
                return new Location(p.lat(), p.lon(), p.nx(), p.ny(), p.fullName());
            }
            double[] c = GeoService.geocode(city);
            if (c == null) {
                throw new IllegalArgumentException("지원하지 않는 도시입니다: " + city.trim());
            }
            return new Location(c[0], c[1]);
        }
        throw new IllegalArgumentException("위치 정보(lat, lon 또는 city)가 필요합니다.");
    }
}
