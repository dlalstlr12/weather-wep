package com.example.weather.location;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.weather.db.PostgisIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** 실제 PostGIS에서 이름 검색, 반경 검색, 최근접 조회를 확인한다. 테스트 데이터는 트랜잭션 안에서만 존재한다. */
@Transactional
class LocationRepositoryTest extends PostgisIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    LocationRepository repo;

    // 테스트용 표본 (좌표는 각 지역 중심 근처의 근사값이며 기상청 공식 값이 아니다)
    @BeforeEach
    void seed() {
        jdbc.update("delete from locations");
        insert("서울특별시", "서울특별시", null, null, 60, 127, 37.5665, 126.9780);
        insert("서울특별시 종로구", "서울특별시", "종로구", null, 60, 127, 37.5730, 126.9794);
        insert("서울특별시 강남구 역삼1동", "서울특별시", "강남구", "역삼1동", 61, 126, 37.5000, 127.0360);
        insert("서울특별시 강남구 역삼2동", "서울특별시", "강남구", "역삼2동", 61, 126, 37.4950, 127.0450);
        insert("부산광역시", "부산광역시", null, null, 98, 76, 35.1796, 129.0756);
        insert("광주광역시", "광주광역시", null, null, 58, 74, 35.1595, 126.8526);
        insert("경기도 광주시", "경기도", "광주시", null, 69, 66, 37.4294, 127.2550);
        insert("제주특별자치도", "제주특별자치도", null, null, 52, 38, 33.4996, 126.5312);
    }

    private void insert(String full, String sido, String sigungu, String dong, int nx, int ny, double lat, double lon) {
        jdbc.update("insert into locations (sido, sigungu, eupmyeondong, full_name, nx, ny, geom) "
                        + "values (?, ?, ?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)",
                sido, sigungu, dong, full, nx, ny, lon, lat);
    }

    @Test
    void searchMatchesPartOfTheNameAndPrefersHigherLevelRegions() {
        assertEquals("서울특별시", repo.search("서울", 5).get(0).fullName());
        assertEquals(List.of("서울특별시 강남구 역삼1동", "서울특별시 강남구 역삼2동"),
                repo.search("역삼", 5).stream().map(Place::fullName).toList());
    }

    @Test
    void searchPrefersGwangjuMetropolitanCityOverGyeonggiGwangju() {
        assertEquals("광주광역시", repo.findBestMatch("광주").orElseThrow().fullName());
    }

    @Test
    void searchTreatsPercentAndUnderscoreAsPlainCharacters() {
        assertTrue(repo.search("%", 5).isEmpty());
        assertTrue(repo.search("_", 5).isEmpty());
    }

    @Test
    void searchReturnsOfficialGridAndCoordinates() {
        Place p = repo.findBestMatch("부산").orElseThrow();
        assertEquals(98, p.nx());
        assertEquals(76, p.ny());
        assertEquals(35.1796, p.lat(), 1e-6);
        assertEquals(129.0756, p.lon(), 1e-6);
    }

    @Test
    void nearbyReturnsPlacesWithinTheRadiusNearestFirst() {
        // 역삼1동 근처에서 3km 안: 역삼1동, 역삼2동만. 서울 시청 쪽은 약 9km라 제외
        List<Place> near = repo.nearby(37.5010, 127.0370, 3000, 10);
        assertEquals(List.of("서울특별시 강남구 역삼1동", "서울특별시 강남구 역삼2동"),
                near.stream().map(Place::fullName).toList());
        assertTrue(near.get(0).distanceMeters() < near.get(1).distanceMeters());
        assertTrue(near.get(0).distanceMeters() < 500);
    }

    @Test
    void nearbyRespectsLimit() {
        assertEquals(1, repo.nearby(37.5010, 127.0370, 50_000, 1).size());
    }

    @Test
    void nearestPicksTheClosestPlaceRegardlessOfDistance() {
        // 제주 근처 좌표에서는 반경에 아무것도 없어도 가장 가까운 곳은 제주
        assertEquals("제주특별자치도", repo.nearest(33.40, 126.60).orElseThrow().fullName());
        assertTrue(repo.nearby(33.40, 126.60, 1000, 10).isEmpty());
    }
}
