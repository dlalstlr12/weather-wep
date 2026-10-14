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
}
