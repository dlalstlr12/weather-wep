package com.example.weather.location;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.weather.db.PostgisIntegrationTest;
import com.example.weather.util.KmaGridConverter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** V4로 적재한 기상청 행정구역 격자 데이터(3,836행)를 대상으로 한 검증. 데이터를 바꾸면 기대값도 함께 갱신해야 한다. */
class RealLocationDataTest extends PostgisIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    LocationRepository repo;

    @Test
    void allRowsAreLoadedWithUniqueCodesAndValidCoordinates() {
        assertEquals(3836, repo.count());
        assertEquals(3836, jdbc.queryForObject("select count(distinct region_code) from locations", Integer.class));
        // 대한민국 안의 좌표만 적재됨 (경위도가 0인 이어도 2행은 제외)
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from locations
                where ST_X(geom::geometry) not between 124 and 132 or ST_Y(geom::geometry) not between 33 and 39
                """, Integer.class));
    }

    @Test
    void topLevelRegionsAreFoundByShortName() {
        assertGrid("서울", "서울특별시", 60, 127);
        assertGrid("부산", "부산광역시", 98, 76);
        assertGrid("대구", "대구광역시", 89, 90);
        assertGrid("제주", "제주특별자치도", 52, 38);
    }

    @Test
    void gwangjuResolvesToTheMergedMetropolitanRegionInTheCurrentData() {
        // 2026년 자료에서는 광주광역시가 '전남광주통합특별시'로 바뀌어 있다
        assertEquals("전남광주통합특별시", repo.findBestMatch("광주").orElseThrow().fullName());
    }

    @Test
    void neighborhoodNameFindsTheDong() {
        List<Place> found = repo.search("역삼", 10);
        assertFalse(found.isEmpty());
        assertTrue(found.stream().anyMatch(p -> p.fullName().equals("서울특별시 강남구 역삼1동")), found.toString());
    }

    @Test
    void nearbyAroundSeoulCityHallFindsCentralSeoul() {
        List<Place> near = repo.nearby(37.5665, 126.9780, 2000, 5);
        assertFalse(near.isEmpty());
        assertTrue(near.get(0).fullName().startsWith("서울특별시"), near.toString());
        assertEquals(60, near.get(0).nx());
        assertEquals(127, near.get(0).ny());
    }

    @Test
    void nearestFromOffshoreJejuPointIsInJeju() {
        assertTrue(repo.nearest(33.30, 126.55).orElseThrow().fullName().startsWith("제주특별자치도"));
    }

    /**
     * 좌표에서 격자를 계산하는 KmaGridConverter와 기상청 공식 표의 격자가 항상 같지는 않다.
     * 3,836곳 중 43곳(1.1%)이 한 칸 어긋나서, 이름으로 찾은 지역은 표의 격자를 그대로 쓴다.
     */
    @Test
    void computedGridDiffersFromTheOfficialGridForSomeRegions() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select nx, ny, ST_Y(geom::geometry) as lat, ST_X(geom::geometry) as lon from locations");
        long mismatches = rows.stream().filter(r -> {
            KmaGridConverter.Grid g = KmaGridConverter.toGrid(((Number) r.get("lat")).doubleValue(), ((Number) r.get("lon")).doubleValue());
            return g.nx() != ((Number) r.get("nx")).intValue() || g.ny() != ((Number) r.get("ny")).intValue();
        }).count();
        assertEquals(43, mismatches);
    }

    private void assertGrid(String query, String expectedName, int nx, int ny) {
        Place p = repo.findBestMatch(query).orElseThrow();
        assertEquals(expectedName, p.fullName());
        assertEquals(nx, p.nx());
        assertEquals(ny, p.ny());
    }
}
