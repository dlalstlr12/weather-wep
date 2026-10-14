package com.example.weather.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LocationResolverTest {

    private final LocationResolver resolver = new LocationResolver();

    @Test
    void coordinatesWinOverCity() {
        assertEquals(new LocationResolver.Location(35.0, 129.0), resolver.resolve(35.0, 129.0, "서울"));
    }

    @Test
    void cityIsGeocodedWhenNoCoordinates() {
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
