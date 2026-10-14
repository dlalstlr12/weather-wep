package com.example.weather.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** 테스트에서 시간을 직접 움직이는 Clock. */
final class TestClock extends Clock {

    private final ZoneId zone;
    private volatile Instant now;

    TestClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    static TestClock seoul(String isoLocal) {
        ZoneId seoul = ZoneId.of("Asia/Seoul");
        return new TestClock(java.time.LocalDateTime.parse(isoLocal).atZone(seoul).toInstant(), seoul);
    }

    void advance(Duration d) {
        now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new TestClock(now, zone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
