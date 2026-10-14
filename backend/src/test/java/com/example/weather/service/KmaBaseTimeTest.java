package com.example.weather.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** 기준 시각 계산 경계 테스트. 시각은 Asia/Seoul 기준 고정 Clock으로 주입한다. */
class KmaBaseTimeTest {

    private static KmaBaseTime at(String isoLocal) {
        ZoneId seoul = ZoneId.of("Asia/Seoul");
        Instant instant = java.time.LocalDateTime.parse(isoLocal).atZone(seoul).toInstant();
        return new KmaBaseTime(Clock.fixed(instant, seoul));
    }

    @Test
    void vilageBeforeFirstSlotUsesPreviousDay2300() {
        assertEquals(new KmaBaseTime.Base("20261013", "2300"), at("2026-10-14T00:30:00").vilage());
        assertEquals(new KmaBaseTime.Base("20261013", "2300"), at("2026-10-14T01:59:00").vilage());
    }

    @Test
    void vilageSlotBoundaries() {
        assertEquals(new KmaBaseTime.Base("20261014", "0200"), at("2026-10-14T02:00:00").vilage());
        assertEquals(new KmaBaseTime.Base("20261014", "0200"), at("2026-10-14T04:59:00").vilage());
        assertEquals(new KmaBaseTime.Base("20261014", "0500"), at("2026-10-14T05:00:00").vilage());
        assertEquals(new KmaBaseTime.Base("20261014", "1100"), at("2026-10-14T13:59:00").vilage());
        assertEquals(new KmaBaseTime.Base("20261014", "2300"), at("2026-10-14T23:59:00").vilage());
    }

    @Test
    void ultraBefore40MinutesUsesPreviousHour() {
        assertEquals(new KmaBaseTime.Base("20261014", "1330"), at("2026-10-14T14:39:00").ultra());
        assertEquals(new KmaBaseTime.Base("20261014", "1430"), at("2026-10-14T14:40:00").ultra());
    }

    @Test
    void ultraJustAfterMidnightRollsBackToPreviousDay() {
        assertEquals(new KmaBaseTime.Base("20261013", "2330"), at("2026-10-14T00:10:00").ultra());
    }

    @Test
    void previousVilageSlot() {
        assertEquals(new KmaBaseTime.Base("20261014", "1100"), KmaBaseTime.previousVilage(new KmaBaseTime.Base("20261014", "1400")));
        assertEquals(new KmaBaseTime.Base("20261014", "0200"), KmaBaseTime.previousVilage(new KmaBaseTime.Base("20261014", "0500")));
        assertEquals(new KmaBaseTime.Base("20261013", "2300"), KmaBaseTime.previousVilage(new KmaBaseTime.Base("20261014", "0200")));
        assertEquals(new KmaBaseTime.Base("20261013", "2000"), KmaBaseTime.previousVilage(new KmaBaseTime.Base("20261013", "2300")));
    }

    @Test
    void previousUltraSlot() {
        assertEquals(new KmaBaseTime.Base("20261014", "1230"), KmaBaseTime.previousUltra(new KmaBaseTime.Base("20261014", "1330")));
        assertEquals(new KmaBaseTime.Base("20261013", "2330"), KmaBaseTime.previousUltra(new KmaBaseTime.Base("20261014", "0030")));
    }
}
