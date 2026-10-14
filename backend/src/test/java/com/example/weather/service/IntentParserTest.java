package com.example.weather.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** IntentParser의 현재 동작을 고정하는 특성화 테스트. */
class IntentParserTest {

    @Test
    void inferCityPrefersExplicitCity() {
        assertEquals("대구", IntentParser.inferCity("대구", "서울 날씨 알려줘"));
    }

    @Test
    void inferCityFromTextKoreanAndEnglish() {
        assertEquals("서울", IntentParser.inferCity(null, "서울 지금 날씨"));
        assertEquals("부산", IntentParser.inferCity("  ", "Busan weather now"));
        assertEquals("용인", IntentParser.inferCity(null, "yongin 내일 날씨"));
    }

    @Test
    void inferCityUnknownReturnsNull() {
        assertNull(IntentParser.inferCity(null, "제주도 날씨"));
        assertNull(IntentParser.inferCity(null, null));
    }

    @Test
    void containsAnyIsCaseInsensitiveAndNullSafe() {
        assertTrue(IntentParser.containsAny("Forecast please", "예보", "forecast"));
        assertFalse(IntentParser.containsAny(null, "예보"));
        assertFalse(IntentParser.containsAny("안녕", "예보", "현재"));
    }

    @Test
    void parseIntentDayOffsets() {
        assertEquals(0, IntentParser.parseIntent("오늘 날씨").dayOffset);
        assertEquals(1, IntentParser.parseIntent("내일 날씨").dayOffset);
        assertEquals(2, IntentParser.parseIntent("모레 날씨").dayOffset);
        assertEquals(1, IntentParser.parseIntent("이번 주 날씨").dayOffset); // 주간은 내일부터
        assertEquals(0, IntentParser.parseIntent(null).dayOffset);
    }

    @Test
    void parseIntentWindowsAndTargetHours() {
        assertEquals("morning", IntentParser.parseIntent("내일 오전").window);
        assertEquals("afternoon", IntentParser.parseIntent("오후에 비 와?").window);
        assertEquals("evening", IntentParser.parseIntent("저녁 기온").window);
        assertEquals("night", IntentParser.parseIntent("밤 날씨").window);
        assertEquals("any", IntentParser.parseIntent("날씨").window);
        assertEquals(9, IntentParser.targetHourForWindow("morning"));
        assertEquals(15, IntentParser.targetHourForWindow("afternoon"));
        assertEquals(19, IntentParser.targetHourForWindow("evening"));
        assertEquals(22, IntentParser.targetHourForWindow("night"));
        assertNull(IntentParser.targetHourForWindow("any"));
        assertNull(IntentParser.targetHourForWindow(null));
    }
}
