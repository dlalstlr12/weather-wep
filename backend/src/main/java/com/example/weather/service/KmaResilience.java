package com.example.weather.service;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
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

    KmaResilience(KmaProperties.Resilience cfg) {
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
        this.breaker.getEventPublisher().onStateTransition(
                e -> log.warn("기상청 서킷 브레이커 상태 전이: {}", e.getStateTransition()));
    }

    <T> T call(Supplier<T> supplier) {
        try {
            return Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(breaker, supplier)).get();
        } catch (CallNotPermittedException e) {
            throw new KmaApiException(KmaApiException.Kind.CIRCUIT_OPEN,
                    "기상청 호출이 일시 차단되었습니다 (서킷 브레이커 열림)", null, e);
        }
    }

    CircuitBreaker.State state() {
        return breaker.getState();
    }
}
