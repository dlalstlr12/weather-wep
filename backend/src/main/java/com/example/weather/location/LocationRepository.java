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

    /** 이름 일부로 검색. 이름이 짧은 것(시·도 같은 상위 행정구역)을 먼저 돌려준다. */
    public List<Place> search(String query, int limit) {
        // LIKE 패턴의 특수문자(% _)와 이스케이프 문자(!)는 일반 문자로 취급한다
        String pattern = "%" + query.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return jdbc.sql("SELECT " + COLUMNS + ", NULL::float8 AS distance_m FROM locations "
                        + "WHERE full_name ILIKE :pattern ESCAPE '!' ORDER BY length(full_name), id LIMIT :limit")
                .param("pattern", pattern).param("limit", limit)
                .query(MAPPER).list();
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
