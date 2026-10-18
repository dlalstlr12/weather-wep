package com.example.weather.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

    private final MeterRegistry registry;

    KmaCache(Clock clock, KmaProperties.Cache cfg) {
        this(clock, cfg, new SimpleMeterRegistry());
    }

    KmaCache(Clock clock, KmaProperties.Cache cfg, MeterRegistry registry) {
        this.registry = registry;
        this.clock = clock;
        this.ttlMillis = cfg.ttlSeconds() * 1000;
        this.staleMaxMillis = cfg.staleMaxSeconds() * 1000;
        this.cache = Caffeine.newBuilder()
                .maximumSize(cfg.maxSize())
                .expireAfterWrite(Duration.ofMillis(staleMaxMillis))
                .build();
        Gauge.builder("kma.cache.size", cache, Cache::estimatedSize).description("캐시에 들어 있는 항목 수").register(registry);
        // 처음 생긴 카운터는 Prometheus의 increase()가 첫 증가를 세지 못하므로 0으로 미리 등록한다
        for (String r : new String[] {"hit", "miss", "coalesced", "stale", "failure", "bypass"}) counter(r);
    }

    /** result: hit(유효한 캐시), miss(새로 불러옴), coalesced(진행 중인 호출을 기다림), stale(실패해서 지난 값으로 대체), failure(실패, 대체할 값 없음), bypass(nocache) */
    private Counter counter(String result) {
        return Counter.builder("kma.cache.requests").description("기상청 캐시 조회 결과").tag("result", result).register(registry);
    }

    private void count(String result) {
        counter(result).increment();
    }

    <T> KmaResult<T> get(String key, boolean nocache, Supplier<T> loader) {
        Entry cached = cache.getIfPresent(key);
        long now = clock.millis();

        if (nocache) {
            count("bypass");
            return result(store(key, loader.get()));
        }
        if (cached != null && now - cached.storedAt < ttlMillis) {
            count("hit");
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
            count("miss");
            return result(fresh);
        } catch (RuntimeException e) {
            if (cached != null && now - cached.storedAt < staleMaxMillis) {
                mine.complete(cached);
                count("stale");
                return result(cached);
            }
            count("failure");
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inflight.remove(key, mine);
        }
    }

    private <T> KmaResult<T> awaitLeader(CompletableFuture<Entry> leader, Entry cached) {
        try {
            KmaResult<T> r = result(leader.get(FOLLOWER_WAIT_SECONDS, TimeUnit.SECONDS));
            count("coalesced");
            return r;
        } catch (ExecutionException e) {
            count("failure");
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new IllegalStateException(e.getCause());
        } catch (TimeoutException e) {
            if (cached != null && clock.millis() - cached.storedAt < staleMaxMillis) {
                count("stale");
                return result(cached);
            }
            count("failure");
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
