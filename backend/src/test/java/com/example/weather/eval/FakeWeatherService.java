package com.example.weather.eval;

import com.example.weather.dto.WeatherDtos;
import com.example.weather.service.LocationResolver;
import com.example.weather.service.WeatherService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 평가용 날씨 대역. 기상청 호출 없이 위치(격자)에서 결정적으로 값을 만든다.
 * 위치마다 값이 달라서 답변이 엉뚱한 위치의 값을 말하면 정답 값 검사에서 잡힌다.
 */
public class FakeWeatherService extends WeatherService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String[] SKY = {"맑음", "구름많음", "흐림"};

    private final LocationResolver resolver;

    public FakeWeatherService(LocationResolver resolver) {
        super(null, resolver);
        this.resolver = resolver;
    }

    static int baseOf(int nx, int ny) {
        return 5 + (nx + ny) % 20;
    }

    static double currentTemp(int base) {
        return base + 0.5;
    }

    static double tempAt(int base, int day, int hour) {
        return base + day + (hour <= 14 ? hour / 3 : (24 - hour) / 3) + (hour % 2 == 0 ? 0.5 : 0.0);
    }

    static int popAt(int base, int day, int hour) {
        return (base * 7 + day * 15 + hour * 3) % 100;
    }

    private int base(Double lat, Double lon, String city) {
        var g = resolver.resolve(lat, lon, city).grid();
        return baseOf(g.nx(), g.ny());
    }

    @Override
    public WeatherDtos.CurrentWeatherResponse getCurrent(Double lat, Double lon, String city, boolean nocache) {
        int base = base(lat, lon, city);
        return new WeatherDtos.CurrentWeatherResponse(currentTemp(base), base % 4 == 0 ? 3.0 : 0.0, SKY[base % 3]);
    }

    @Override
    public WeatherDtos.ForecastResponse getForecast(Double lat, Double lon, String city, boolean nocache) {
        int base = base(lat, lon, city);
        LocalDate today = LocalDate.now(SEOUL);
        List<WeatherDtos.ForecastEntry> items = new ArrayList<>();
        for (int d = 0; d <= 3; d++) {
            String day = today.plusDays(d).format(YMD);
            for (int h = 0; h < 24; h++) {
                int pop = popAt(base, d, h);
                items.add(new WeatherDtos.ForecastEntry(day + String.format("%02d00", h), tempAt(base, d, h),
                        pop >= 60 ? 1.0 : 0.0, SKY[(base + d) % 3], pop, 55, 2.0));
            }
        }
        return new WeatherDtos.ForecastResponse(items);
    }
}
