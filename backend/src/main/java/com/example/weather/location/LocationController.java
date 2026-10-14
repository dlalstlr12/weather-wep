package com.example.weather.location;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/locations")
public class LocationController {

    private final LocationRepository locations;

    public LocationController(LocationRepository locations) {
        this.locations = locations;
    }

    /** 이름 일부로 지역 검색 (예: q=역삼) */
    @GetMapping("/search")
    public List<Place> search(
            @RequestParam @NotBlank @Size(max = 50) String q,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        return locations.search(q, limit);
    }

    /** 좌표 반경 안의 지역을 가까운 순으로 (radiusKm 기본 10km, 최대 100km) */
    @GetMapping("/nearby")
    public List<Place> nearby(
            @RequestParam @DecimalMin("33.0") @DecimalMax("39.0") double lat,
            @RequestParam @DecimalMin("124.0") @DecimalMax("132.0") double lon,
            @RequestParam(defaultValue = "10") @DecimalMin("0.1") @DecimalMax("100") double radiusKm,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        return locations.nearby(lat, lon, radiusKm * 1000, limit);
    }
}
