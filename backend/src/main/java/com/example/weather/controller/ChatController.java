package com.example.weather.controller;

import com.example.weather.chat.ChatSink;
import com.example.weather.chat.StreamClosedException;
import com.example.weather.dto.ChatDtos;
import com.example.weather.service.ChatService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private static final long STREAM_TIMEOUT_MS = 90_000;

    // 모델 응답을 기다리는 블로킹 작업이라 공용 풀(ForkJoin common pool)을 쓰지 않는다.
    // 동시 챗 수는 사용자별·전체 일일 한도가 이미 제한한다.
    private static final ExecutorService STREAM_EXECUTOR = Executors.newCachedThreadPool(new ThreadFactory() {
        private final AtomicInteger n = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "chat-stream-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    private final ChatService chatService;

    @PostMapping
    public ResponseEntity<ChatDtos.ChatResponse> chat(@Valid @RequestBody ChatDtos.ChatRequest req, Authentication auth) {
        return ResponseEntity.ok(chatService.chat(req, auth.getName()));
    }

    /**
     * 답변을 SSE로 흘려 보낸다. 이벤트: status(도구 조회 중 안내), delta(답변 조각), reset(지금까지의 조각 삭제), done, error.
     * 데이터는 JSON이라 줄바꿈이 있어도 깨지지 않는다.
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatDtos.ChatRequest req, Authentication auth) {
        String user = auth.getName();
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        STREAM_EXECUTOR.execute(() -> {
            try {
                chatService.chatStream(req, user, new ChatSink() {
                    @Override
                    public void status(String text) {
                        send(emitter, "status", Map.of("text", text));
                    }

                    @Override
                    public void delta(String text) {
                        send(emitter, "delta", Map.of("text", text));
                    }

                    @Override
                    public void reset() {
                        send(emitter, "reset", Map.of());
                    }
                });
                send(emitter, "done", Map.of());
                emitter.complete();
            } catch (StreamClosedException e) {
                emitter.complete();
            } catch (Exception e) {
                log.warn("챗 스트림 처리 중 오류", e);
                try {
                    send(emitter, "error", Map.of("message", "응답 중 오류가 발생했어요."));
                } catch (StreamClosedException ignored) {
                    // 이미 끊겼다
                }
                emitter.complete();
            }
        });
        emitter.onTimeout(emitter::complete);
        return emitter;
    }

    private static void send(SseEmitter emitter, String event, Map<String, String> data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            throw new StreamClosedException(e);
        }
    }
}
