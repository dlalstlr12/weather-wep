package com.example.weather.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** 기상청 호출·캐시·서킷 브레이커가 실제 동작과 같은 측정값을 남기는지 확인한다. */
class KmaMetricsTest {

    private static final String ULTRA = "/getUltraSrtNcst";

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    private final MeterRegistry registry = new SimpleMeterRegistry();

    @BeforeEach
    void reset() {
        wm.resetAll();
    }

    private double cache(String result) {
        var c = registry.find("kma.cache.requests").tag("result", result).counter();
        return c == null ? 0 : c.count();
    }

    private long attempts(String outcome) {
        var t = registry.find("kma.upstream.attempt").tag("outcome", outcome).timer();
        return t == null ? 0 : t.count();
    }

    private KmaClient client(int retryAttempts, int breakerWindow, int breakerMinCalls, long breakerOpenMs) {
        KmaProperties props = KmaFixtures.props(wm.baseUrl(), new KmaProperties.Http(500, 1000),
                new KmaProperties.Cache(600, 10800, 1000),
                new KmaProperties.Resilience(retryAttempts, 10, breakerWindow, breakerMinCalls, 50f, breakerOpenMs));
        return new KmaClient(props, Clock.system(ZoneId.of("Asia/Seoul")), registry);
    }

    @Test
    void cacheCountsMissThenHitAndBypass() {
        wm.stubFor(get(urlPathEqualTo(ULTRA)).willReturn(okJson(KmaFixtures.ULTRA_OK)));
        KmaClient c = client(1, 100, 100, 1000);

        c.getUltraNowcast(37.5665, 126.9780, false); // 처음: 불러옴
        c.getUltraNowcast(37.5665, 126.9780, false); // 두 번째: 캐시
        c.getUltraNowcast(37.5665, 126.9780, true);  // nocache

        assertEquals(1, cache("miss"));
        assertEquals(1, cache("hit"));
        assertEquals(1, cache("bypass"));
        assertEquals(2, attempts("success")); // 실제 HTTP 호출은 miss와 bypass 두 번
        assertEquals(1.0, registry.find("kma.cache.size").gauge().value());
    }

    @Test
    void cacheAndCircuitCountersExistAtZeroBeforeTheFirstEvent() {
        client(1, 100, 100, 1000);
        for (String r : new String[] {"hit", "miss", "coalesced", "stale", "failure", "bypass"}) {
            assertEquals(0, cache(r) , r);
            org.junit.jupiter.api.Assertions.assertNotNull(registry.find("kma.cache.requests").tag("result", r).counter(), r);
        }
        assertEquals(0, registry.find("kma.circuit.transitions").tag("to", "CLOSED").counter().count());
    }

    @Test
    void cacheCountsStaleWhenReloadFailsAndFailureWhenNothingToServe() {
        TestClock clock = TestClock.seoul("2026-10-14T12:00:00");
        KmaCache cache = new KmaCache(clock, new KmaProperties.Cache(600, 10800, 100), registry);
        cache.get("k", false, () -> "old");
        clock.advance(Duration.ofSeconds(900));
        cache.get("k", false, () -> {
            throw new KmaApiException(KmaApiException.Kind.UNREACHABLE, "down", null, null);
        });
        assertEquals(1, cache("stale"));

        assertThrows(KmaApiException.class, () -> cache.get("other", false, () -> {
            throw new KmaApiException(KmaApiException.Kind.UNREACHABLE, "down", null, null);
        }));
        assertEquals(1, cache("failure"));
    }

    @Test
    void concurrentRequestsForTheSameKeyAreCountedAsCoalesced() throws Exception {
        KmaCache cache = new KmaCache(TestClock.seoul("2026-10-14T12:00:00"), new KmaProperties.Cache(600, 10800, 100), registry);
        CountDownLatch leaderStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> leader = pool.submit(() -> cache.get("k", false, () -> {
                leaderStarted.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "v";
            }));
            leaderStarted.await();
            Future<?> follower = pool.submit(() -> cache.get("k", false, () -> "never"));
            Thread.sleep(200); // 후발 요청이 선두를 기다리는 상태가 되도록
            release.countDown();
            leader.get();
            follower.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, cache("miss"));
        assertEquals(1, cache("coalesced"));
    }

    @Test
    void upstreamAttemptsAreRecordedPerOutcomeAndRetriesArePerAttempt() {
        wm.stubFor(get(urlPathEqualTo(ULTRA)).willReturn(aResponse().withStatus(500)));
        assertThrows(KmaApiException.class, () -> client(3, 100, 100, 1000).getUltraNowcast(37.5665, 126.9780, true));

        assertEquals(3, attempts("HTTP_ERROR"));
        assertEquals(2, registry.find("kma.retry.attempts").counter().count());
        assertEquals(0, attempts("success"));
    }

    @Test
    void circuitBreakerStateTransitionsAndRejectionsAreRecorded() {
        wm.stubFor(get(urlPathEqualTo(ULTRA)).willReturn(aResponse().withStatus(500)));
        KmaClient c = client(1, 4, 4, 60_000);
        assertEquals(0.0, registry.find("kma.circuit.state").gauge().value()); // 닫힘

        for (int i = 0; i < 4; i++) {
            assertThrows(KmaApiException.class, () -> c.getUltraNowcast(37.5665, 126.9780, true));
        }
        assertEquals(1.0, registry.find("kma.circuit.state").gauge().value()); // 열림
        assertEquals(1, registry.find("kma.circuit.transitions").tag("to", "OPEN").counter().count());

        KmaApiException e = assertThrows(KmaApiException.class, () -> c.getUltraNowcast(37.5665, 126.9780, true));
        assertEquals(KmaApiException.Kind.CIRCUIT_OPEN, e.kind());
        assertEquals(1, registry.find("kma.circuit.rejected").counter().count());
        assertEquals(4, attempts("HTTP_ERROR")); // 거절된 호출은 실제 시도로 세지 않는다
    }
}
