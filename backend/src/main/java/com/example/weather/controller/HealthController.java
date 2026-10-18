package com.example.weather.controller;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 프론트가 서버가 쓸 수 있는 상태인지 확인하는 공개 엔드포인트. 무료 호스팅은 한동안 요청이 없으면 서버가 잠들어
 * 첫 접속 때 깨어나는 동안 로그인과 챗이 실패하므로, 화면이 이 값으로 안내를 보여 준다.
 * 로그인이 DB를 쓰기 때문에 DB 연결까지 확인한다. DB가 끊기면 커넥션 풀이 10초를 기다리므로(connection-timeout),
 * 점검은 별도 스레드에서 짧은 제한 시간(기본 3초) 안에만 기다리고 넘으면 DOWN으로 답한다. 오류 내용은 응답에 담지 않는다.
 * (측정용 관리 포트의 /actuator/health와는 별개다: 그쪽은 서비스 포트로 열리지 않는다)
 */
@Slf4j
@RestController
@RequestMapping("/api/health")
public class HealthController {

    // 점검이 오래 걸리는 동안 요청이 쌓여 스레드가 늘지 않도록 작은 고정 풀을 쓴다 (꽉 차면 대기하다 제한 시간에 DOWN)
    private static final ExecutorService PING = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "health-ping");
        t.setDaemon(true);
        return t;
    });

    private final JdbcClient jdbc;
    private final long timeoutMs;

    @Autowired
    public HealthController(JdbcClient jdbc) {
        this(jdbc, 3000);
    }

    HealthController(JdbcClient jdbc, long timeoutMs) {
        this.jdbc = jdbc;
        this.timeoutMs = timeoutMs;
    }

    @GetMapping
    public ResponseEntity<Map<String, String>> health() {
        Future<Integer> ping = PING.submit(() -> jdbc.sql("select 1").query(Integer.class).single());
        try {
            ping.get(timeoutMs, TimeUnit.MILLISECONDS);
            return ResponseEntity.ok(Map.of("status", "UP"));
        } catch (TimeoutException | ExecutionException e) {
            ping.cancel(true);
            log.warn("health: 데이터베이스 확인 실패({})", e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ping.cancel(true);
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "DOWN", "reason", "database"));
    }
}
