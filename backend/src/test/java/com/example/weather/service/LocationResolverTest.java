package com.example.weather.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.weather.location.LocationRepository;
import com.example.weather.location.Place;
import com.example.weather.util.KmaGridConverter;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocationResolverTest {

    private LocationRepository repo;
    private LocationResolver resolver;

    @BeforeEach
    void setUp() {
        repo = mock(LocationRepository.class);
        when(repo.findBestMatch(anyString())).thenReturn(Optional.empty());
        resolver = new LocationResolver(repo);
    }

    @Test
    void coordinatesWinOverCityAndSkipTheDatabase() {
        assertEquals(new LocationResolver.Location(35.0, 129.0), resolver.resolve(35.0, 129.0, "서울"));
        verifyNoInteractions(repo);
    }

    @Test
    void cityIsLookedUpInTheLocationTableAndKeepsTheOfficialGrid() {
        when(repo.findBestMatch("역삼")).thenReturn(Optional.of(
                new Place(1, "서울특별시 강남구 역삼1동", "서울특별시", "강남구", "역삼1동", 61, 126, 37.4999, 127.0329, null)));
        LocationResolver.Location loc = resolver.resolve(null, null, "역삼");
        assertEquals(61, loc.nx());
        assertEquals(126, loc.ny());
        assertEquals("서울특별시 강남구 역삼1동", loc.name());
        // 좌표에서 계산하지 않고 표의 격자를 그대로 쓴다
        assertEquals(new KmaGridConverter.Grid(61, 126), loc.grid());
    }

    @Test
    void coordinateOnlyLocationComputesTheGrid() {
        LocationResolver.Location loc = new LocationResolver.Location(37.5665, 126.9780);
        assertEquals(KmaGridConverter.toGrid(37.5665, 126.9780), loc.grid());
        assertNull(loc.nx());
    }

    @Test
    void fallsBackToTheBuiltInCitiesWhenTheTableHasNoMatch() {
        assertEquals(new LocationResolver.Location(37.5665, 126.9780), resolver.resolve(null, null, " 서울 "));
        assertEquals(new LocationResolver.Location(35.1796, 129.0756), resolver.resolve(null, null, "Busan"));
    }

    @Test
    void halfCoordinatesFallBackToCity() {
        assertEquals(new LocationResolver.Location(37.5665, 126.9780), resolver.resolve(37.0, null, "서울"));
    }

    @Test
    void unknownOrMissingLocationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(null, null, "제주"));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(null, null, "  "));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(null, null, null));
    }
}
