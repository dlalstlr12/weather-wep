package com.example.weather.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 기상청 응답 캐시.
 * - ttl 안의 값은 그대로 쓴다.
 * - ttl이 지나면 새로 불러오되, 같은 키를 동시에 요청해도 불러오는 호출은 하나만 보낸다(single-flight).
 * - 새로 불러오기에 실패했고 staleMax 안의 지난 값이 있으면 그 값을 stale로 표시해 대신 준다.
 * - nocache이면 캐시를 우회해 항상 불러오고(합치지도, 지난 값으로 대체하지도 않음) 결과만 저장한다.
 */
final class KmaCache {

    private static final long FOLLOWER_WAIT_SECONDS = 10;

    private record Entry(Object value, long storedAt) {}

    private final Cache<String, Entry> cache;
    private final ConcurrentHashMap<String, CompletableFuture<Entry>> inflight = new ConcurrentHashMap<>();
    private final Clock clock;
    private final long ttlMillis;
    private final long staleMaxMillis;

    KmaCache(Clock clock, KmaProperties.Cache cfg) {
        this.clock = clock;
        this.ttlMillis = cfg.ttlSeconds() * 1000;
        this.staleMaxMillis = cfg.staleMaxSeconds() * 1000;
        this.cache = Caffeine.newBuilder()
                .maximumSize(cfg.maxSize())
                .expireAfterWrite(Duration.ofMillis(staleMaxMillis))
                .build();
    }

    <T> KmaResult<T> get(String key, boolean nocache, Supplier<T> loader) {
        Entry cached = cache.getIfPresent(key);
        long now = clock.millis();

        if (nocache) {
            return result(store(key, loader.get()));
        }
        if (cached != null && now - cached.storedAt < ttlMillis) {
            return result(cached);
        }

        CompletableFuture<Entry> mine = new CompletableFuture<>();
        CompletableFuture<Entry> leader = inflight.putIfAbsent(key, mine);
        if (leader != null) {
            return awaitLeader(leader, cached);
        }
        try {
            Entry fresh = store(key, loader.get());
            mine.complete(fresh);
            return result(fresh);
        } catch (RuntimeException e) {
            if (cached != null && now - cached.storedAt < staleMaxMillis) {
                mine.complete(cached);
                return result(cached);
            }
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inflight.remove(key, mine);
        }
    }

    private <T> KmaResult<T> awaitLeader(CompletableFuture<Entry> leader, Entry cached) {
        try {
            return result(leader.get(FOLLOWER_WAIT_SECONDS, TimeUnit.SECONDS));
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new IllegalStateException(e.getCause());
        } catch (TimeoutException e) {
            if (cached != null && clock.millis() - cached.storedAt < staleMaxMillis) return result(cached);
            throw new KmaApiException(KmaApiException.Kind.UNREACHABLE, "기상청 조회 대기 시간 초과", null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KmaApiException(KmaApiException.Kind.UNREACHABLE, "기상청 조회가 중단되었습니다", null, e);
        }
    }

    private Entry store(String key, Object value) {
        Entry e = new Entry(value, clock.millis());
        cache.put(key, e);
        return e;
    }

    @SuppressWarnings("unchecked")
    private <T> KmaResult<T> result(Entry e) {
        long ageMillis = Math.max(0, clock.millis() - e.storedAt);
        return new KmaResult<>((T) e.value, ageMillis >= ttlMillis, ageMillis / 1000);
    }
}
