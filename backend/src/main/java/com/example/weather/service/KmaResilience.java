package com.example.weather.service;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * 기상청 호출 한 번을 재시도(Retry)와 서킷 브레이커로 감싼다.
 * 재시도가 바깥, 서킷 브레이커가 안쪽이라 시도마다 브레이커에 기록되고, 브레이커가 열리면 재시도하지 않고 바로 실패한다.
 */
@Slf4j
final class KmaResilience {

    private final Retry retry;
    private final CircuitBreaker breaker;
    private final Counter rejected;

    KmaResilience(KmaProperties.Resilience cfg) {
        this(cfg, new SimpleMeterRegistry());
    }

    KmaResilience(KmaProperties.Resilience cfg, MeterRegistry registry) {
        this.retry = Retry.of("kma", RetryConfig.custom()
                .maxAttempts(cfg.retryMaxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(cfg.retryWaitMs()), 2.0))
                .retryOnException(e -> e instanceof KmaApiException k && k.isRetryable())
                .build());
        this.breaker = CircuitBreaker.of("kma", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(cfg.breakerWindowSize())
                .minimumNumberOfCalls(cfg.breakerMinimumCalls())
                .failureRateThreshold(cfg.breakerFailureRatePercent())
                .waitDurationInOpenState(Duration.ofMillis(cfg.breakerOpenMs()))
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordException(e -> !(e instanceof KmaApiException k) || k.countsAsUpstreamFailure())
                .ignoreExceptions(CallNotPermittedException.class)
                .build());
        this.breaker.getEventPublisher().onStateTransition(e -> {
            log.warn("기상청 서킷 브레이커 상태 전이: {}", e.getStateTransition());
            transitions(registry, e.getStateTransition().getToState().name()).increment();
        });
        // 처음 생긴 카운터는 Prometheus의 increase()가 첫 증가를 세지 못하므로 0으로 미리 등록한다
        for (String to : new String[] {"CLOSED", "OPEN", "HALF_OPEN"}) transitions(registry, to);
        this.retry.getEventPublisher().onRetry(e -> Counter.builder("kma.retry.attempts")
                .description("기상청 호출 재시도 횟수").register(registry).increment());
        this.rejected = Counter.builder("kma.circuit.rejected")
                .description("서킷 브레이커가 열려 호출하지 않고 거절한 횟수").register(registry);
        Gauge.builder("kma.circuit.state", breaker, b -> stateCode(b.getState()))
                .description("서킷 브레이커 상태 (0=닫힘, 1=열림, 2=반열림)").register(registry);
    }

    private static Counter transitions(MeterRegistry registry, String to) {
        return Counter.builder("kma.circuit.transitions").description("서킷 브레이커 상태 전이 횟수").tag("to", to).register(registry);
    }

    private static int stateCode(CircuitBreaker.State state) {
        return switch (state) {
            case CLOSED -> 0;
            case OPEN, FORCED_OPEN -> 1;
            case HALF_OPEN -> 2;
            default -> 3;
        };
    }

    <T> T call(Supplier<T> supplier) {
        try {
            return Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(breaker, supplier)).get();
        } catch (CallNotPermittedException e) {
            rejected.increment();
            throw new KmaApiException(KmaApiException.Kind.CIRCUIT_OPEN,
                    "기상청 호출이 일시 차단되었습니다 (서킷 브레이커 열림)", null, e);
        }
    }

    CircuitBreaker.State state() {
        return breaker.getState();
    }
}
