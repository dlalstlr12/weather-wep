package com.example.weather.service;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** 기상청 응답용 TTL 캐시. nocache이면 항상 새로 불러오고 결과는 다시 저장한다. */
final class KmaCache {

    private static final long TTL_MILLIS = 10 * 60 * 1000L;

    private record Entry(Object data, long storedAt) {}

    private final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();
    private final Clock clock;

    KmaCache(Clock clock) {
        this.clock = clock;
    }

    @SuppressWarnings("unchecked")
    <T> T get(String key, boolean nocache, Supplier<T> loader) {
        Entry e = map.get(key);
        if (!nocache && e != null && clock.millis() - e.storedAt < TTL_MILLIS) {
            return (T) e.data;
        }
        T value = loader.get();
        map.put(key, new Entry(value, clock.millis()));
        return value;
    }
}
