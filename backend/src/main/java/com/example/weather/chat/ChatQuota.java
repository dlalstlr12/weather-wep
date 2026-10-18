package com.example.weather.chat;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * LLM 사용 횟수 상한(사용자별·전체, 하루 단위 KST). 크레딧이 한정돼 있어 남용과 폭주를 막는 장치이고, 넘으면 규칙 기반 답변(무료)으로 내려간다.
 * ponytail: 서버 메모리에만 저장하므로 재시작하면 0으로 돌아가고 인스턴스가 여럿이면 각자 센다. 필요해지면 DB나 Redis로 옮긴다.
 */
@Component
public class ChatQuota {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final Cache<String, AtomicInteger> counts = Caffeine.newBuilder().expireAfterWrite(Duration.ofDays(1)).build();
    private final AnthropicProperties props;
    private final Clock clock;

    @Autowired
    public ChatQuota(AnthropicProperties props) {
        this(props, Clock.system(SEOUL));
    }

    ChatQuota(AnthropicProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    /** 한도 안이면 횟수를 하나 쓰고 true. 한도를 넘었으면 쓰지 않고 false. */
    public synchronized boolean tryAcquire(String user) {
        String day = LocalDate.now(clock).toString();
        AtomicInteger total = counts.get(day + ":*", k -> new AtomicInteger());
        AtomicInteger mine = counts.get(day + ":" + user, k -> new AtomicInteger());
        if (total.get() >= props.dailyLimitTotal() || mine.get() >= props.dailyLimitPerUser()) return false;
        total.incrementAndGet();
        mine.incrementAndGet();
        return true;
    }
}
