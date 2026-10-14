package com.example.weather.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 기상청 격자 변환의 현재 동작을 고정하는 특성화 테스트 (기상청 공개 격자표의 대표 지점 값). */
class KmaGridConverterTest {

    @Test
    void seoul() {
        assertEquals(new KmaGridConverter.Grid(60, 127), KmaGridConverter.toGrid(37.5665, 126.9780));
    }

    @Test
    void busan() {
        assertEquals(new KmaGridConverter.Grid(98, 76), KmaGridConverter.toGrid(35.1796, 129.0756));
    }

    @Test
    void jeju() {
        assertEquals(new KmaGridConverter.Grid(53, 38), KmaGridConverter.toGrid(33.4996, 126.5312));
    }
}
