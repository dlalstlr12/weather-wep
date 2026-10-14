package com.example.weather.location;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.weather.controller.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class LocationControllerTest {

    private LocationRepository repo;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        repo = mock(LocationRepository.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new LocationController(repo))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void searchReturnsPlaces() throws Exception {
        when(repo.search("역삼", 10)).thenReturn(List.of(
                new Place(1, "서울특별시 강남구 역삼1동", "서울특별시", "강남구", "역삼1동", 61, 126, 37.5, 127.03, null)));
        mvc.perform(get("/api/locations/search").param("q", "역삼"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fullName").value("서울특별시 강남구 역삼1동"))
                .andExpect(jsonPath("$[0].nx").value(61));
    }

    @Test
    void nearbyConvertsKilometersToMeters() throws Exception {
        when(repo.nearby(anyDouble(), anyDouble(), anyDouble(), anyInt())).thenReturn(List.of());
        mvc.perform(get("/api/locations/nearby").param("lat", "37.5").param("lon", "127.0").param("radiusKm", "2.5"))
                .andExpect(status().isOk());
        verify(repo).nearby(eq(37.5), eq(127.0), eq(2500.0), eq(10));
    }

    @Test
    void invalidParametersAreRejectedWith400() throws Exception {
        mvc.perform(get("/api/locations/search").param("q", " ")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/locations/search").param("q", "서울").param("limit", "999")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/locations/nearby").param("lat", "10").param("lon", "127")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/locations/nearby").param("lat", "37.5").param("lon", "127").param("radiusKm", "500"))
                .andExpect(status().isBadRequest());
    }
}
