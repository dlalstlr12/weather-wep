package com.example.weather.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class KmaCacheTest {

    // ttl 600초, 지난 값은 3시간(10800초)까지 대신 사용
    private final TestClock clock = TestClock.seoul("2026-10-14T12:00:00");
    private final KmaCache cache = new KmaCache(clock, new KmaProperties.Cache(600, 10800, 100));

    @Test
    void freshValueIsServedWithoutCallingTheLoaderAgain() {
        AtomicInteger calls = new AtomicInteger();
        cache.get("k", false, () -> "v" + calls.incrementAndGet());
        clock.advance(Duration.ofSeconds(599));
        KmaResult<String> r = cache.get("k", false, () -> "v" + calls.incrementAndGet());
        assertEquals("v1", r.value());
        assertFalse(r.stale());
        assertEquals(1, calls.get());
    }

    @Test
    void valueIsReloadedAfterTtl() {
        AtomicInteger calls = new AtomicInteger();
        cache.get("k", false, () -> "v" + calls.incrementAndGet());
        clock.advance(Duration.ofSeconds(601));
        KmaResult<String> r = cache.get("k", false, () -> "v" + calls.incrementAndGet());
        assertEquals("v2", r.value());
        assertFalse(r.stale());
    }

    @Test
    void whenReloadFailsTheOldValueIsServedAsStale() {
        cache.get("k", false, () -> "old");
        clock.advance(Duration.ofSeconds(900));
        KmaResult<String> r = cache.get("k", false, () -> {
            throw new KmaApiException(KmaApiException.Kind.UNREACHABLE, "down", null, null);
        });
        assertEquals("old", r.value());
        assertTrue(r.stale());
        assertEquals(900, r.ageSeconds());
    }

    @Test
    void whenReloadFailsAndTheOldValueIsTooOldTheErrorPropagates() {
        cache.get("k", false, () -> "old");
        clock.advance(Duration.ofSeconds(10801));
        assertThrows(KmaApiException.class, () -> cache.get("k", false, () -> {
            throw new KmaApiException(KmaApiException.Kind.UNREACHABLE, "down", null, null);
        }));
    }

    @Test
    void failureWithoutAnyCachedValuePropagates() {
        assertThrows(KmaApiException.class, () -> cache.get("k", false, () -> {
            throw new KmaApiException(KmaApiException.Kind.HTTP_ERROR, "500", null, 500, null);
        }));
    }

    @Test
    void nocacheAlwaysReloadsAndNeverFallsBackToTheOldValue() {
        AtomicInteger calls = new AtomicInteger();
        cache.get("k", false, () -> "v" + calls.incrementAndGet());
        assertEquals("v2", cache.get("k", true, () -> "v" + calls.incrementAndGet()).value());
        assertThrows(KmaApiException.class, () -> cache.get("k", true, () -> {
            throw new KmaApiException(KmaApiException.Kind.UNREACHABLE, "down", null, null);
        }));
    }

    @Test
    void concurrentRequestsForTheSameKeyShareOneLoad() throws Exception {
        int threads = 20;
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<KmaResult<String>>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return cache.get("same", false, () -> {
                    loads.incrementAndGet();
                    sleep(300);
                    return "value";
                });
            }));
        }
        start.countDown();
        for (Future<KmaResult<String>> f : futures) assertEquals("value", f.get().value());
        pool.shutdown();
        assertEquals(1, loads.get());
    }

    @Test
    void differentKeysLoadIndependently() {
        AtomicInteger loads = new AtomicInteger();
        cache.get("a", false, () -> loads.incrementAndGet());
        cache.get("b", false, () -> loads.incrementAndGet());
        assertEquals(2, loads.get());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
