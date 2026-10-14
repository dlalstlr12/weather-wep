package com.example.weather.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 기상청 조회 기준 날짜·시각(base_date, base_time) 계산. 현재 시각은 Clock으로 주입한다. */
final class KmaBaseTime {

    record Base(String date, String time) {}

    private static final int[] VILAGE_HOURS = {2, 5, 8, 11, 14, 17, 20, 23};
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH");
    private static final DateTimeFormatter DATE_HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH");

    private final Clock clock;

    KmaBaseTime(Clock clock) {
        this.clock = clock;
    }

    /** 초단기실황: 매시각 40분 이후 최신 자료 제공 → 40분 미만이면 이전 시각의 30분, 아니면 해당 시각의 30분 */
    Base ultra() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime base = now.getMinute() < 40 ? now.minusHours(1) : now;
        return new Base(base.format(DATE), base.format(HOUR) + "30");
    }

    /** 단기예보: 02,05,08,11,14,17,20,23시 정시 중 가장 최근 것. 00~01시는 전날 23시 */
    Base vilage() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime candidate = now.withMinute(0).withSecond(0).withNano(0);
        int chosen = -1;
        for (int i = VILAGE_HOURS.length - 1; i >= 0; i--) {
            if (now.getHour() >= VILAGE_HOURS[i]) {
                chosen = VILAGE_HOURS[i];
                break;
            }
        }
        candidate = chosen == -1 ? candidate.minusDays(1).withHour(23) : candidate.withHour(chosen);
        return new Base(candidate.format(DATE), candidate.format(HOUR) + "00");
    }

    /** base 바로 앞 단기예보 발표 슬롯 (02시 앞은 전날 23시) */
    static Base previousVilage(Base base) {
        LocalDateTime t = LocalDateTime.parse(base.date() + base.time().substring(0, 2), DATE_HOUR);
        int prev = -1;
        for (int h : VILAGE_HOURS) {
            if (h < t.getHour()) prev = h;
        }
        LocalDateTime p = prev == -1 ? t.minusDays(1).withHour(23) : t.withHour(prev);
        return new Base(p.format(DATE), p.format(HOUR) + "00");
    }

    /** base 바로 앞 초단기실황 슬롯 (한 시간 전의 30분) */
    static Base previousUltra(Base base) {
        LocalDateTime t = LocalDateTime.parse(base.date() + base.time().substring(0, 2), DATE_HOUR).minusHours(1);
        return new Base(t.format(DATE), t.format(HOUR) + "30");
    }
}
