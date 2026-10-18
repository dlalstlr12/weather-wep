package com.example.weather.location;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** PostGIS 위치 테이블 조회. 공간 쿼리라 JPA 대신 네이티브 SQL을 쓴다. */
@Repository
public class LocationRepository {

    private static final String POINT = "ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography";
    private static final String COLUMNS =
            "id, full_name, sido, sigungu, eupmyeondong, nx, ny, ST_Y(geom::geometry) AS lat, ST_X(geom::geometry) AS lon";

    private static final RowMapper<Place> MAPPER = (rs, i) -> new Place(
            rs.getLong("id"), rs.getString("full_name"), rs.getString("sido"), rs.getString("sigungu"),
            rs.getString("eupmyeondong"), rs.getInt("nx"), rs.getInt("ny"),
            rs.getDouble("lat"), rs.getDouble("lon"),
            rs.getObject("distance_m") == null ? null : rs.getDouble("distance_m"));

    private final JdbcClient jdbc;

    public LocationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 이름 일부로 검색. 상위 행정구역(시·도, 시·군·구)을 먼저, 같은 단계에서는 이름이 짧은 것을 먼저 돌려준다. */
    public List<Place> search(String query, int limit) {
        List<Place> found = searchByName(query, limit);
        String q = query.trim();
        // "제주도", "서울시"처럼 정식 이름에 없는 접미사(도, 시)가 붙은 입력은 접미사를 떼고 한 번 더 찾는다
        if (found.isEmpty() && q.length() > 2 && (q.endsWith("도") || q.endsWith("시"))) {
            return searchByName(q.substring(0, q.length() - 1), limit);
        }
        return found;
    }

    private List<Place> searchByName(String query, int limit) {
        // 공백으로 나눈 단어가 모두 이름에 들어 있어야 한다 ("부산 해운대구"가 "부산광역시 해운대구"를 찾도록)
        String[] words = query.trim().split("\\s+");
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) where.append(" AND ");
            where.append("full_name ILIKE :p").append(i).append(" ESCAPE '!'");
        }
        JdbcClient.StatementSpec spec = jdbc.sql("SELECT " + COLUMNS + ", NULL::float8 AS distance_m FROM locations WHERE " + where
                + " ORDER BY (sigungu IS NOT NULL)::int + (eupmyeondong IS NOT NULL)::int, length(full_name), id LIMIT :limit")
                .param("limit", limit);
        for (int i = 0; i < words.length; i++) {
            // LIKE 패턴의 특수문자(% _)와 이스케이프 문자(!)는 일반 문자로 취급한다
            spec = spec.param("p" + i, "%" + words[i].replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        return spec.query(MAPPER).list();
    }

    public Optional<Place> findBestMatch(String name) {
        return search(name, 1).stream().findFirst();
    }

    /** 반경(미터) 안의 지역을 가까운 순으로 */
    public List<Place> nearby(double lat, double lon, double radiusMeters, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + ", ST_Distance(geom, " + POINT + ") AS distance_m FROM locations "
                        + "WHERE ST_DWithin(geom, " + POINT + ", :radius) ORDER BY geom <-> " + POINT + " LIMIT :limit")
                .param("lat", lat).param("lon", lon).param("radius", radiusMeters).param("limit", limit)
                .query(MAPPER).list();
    }

    /** 가장 가까운 지역 하나 */
    public Optional<Place> nearest(double lat, double lon) {
        return jdbc.sql("SELECT " + COLUMNS + ", ST_Distance(geom, " + POINT + ") AS distance_m FROM locations "
                        + "ORDER BY geom <-> " + POINT + " LIMIT 1")
                .param("lat", lat).param("lon", lon)
                .query(MAPPER).optional();
    }

    public long count() {
        return jdbc.sql("SELECT count(*) FROM locations").query(Long.class).single();
    }
}
