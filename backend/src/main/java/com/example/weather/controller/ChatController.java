package com.example.weather.controller;

import com.example.weather.dto.ChatDtos;
import com.example.weather.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping
    public ResponseEntity<ChatDtos.ChatResponse> chat(@Valid @RequestBody ChatDtos.ChatRequest req) {
        return ResponseEntity.ok(chatService.chat(req));
    }

    @PostMapping(value = "/stream", produces = "text/event-stream")
    public SseEmitter stream(@Valid @RequestBody ChatDtos.ChatRequest req) {
        SseEmitter emitter = new SseEmitter(0L);
        CompletableFuture.runAsync(() -> {
            try {
                String full = chatService.chat(req).content();
                if (full == null) full = "";
                // chunk by ~80 chars
                byte[] bytes = full.getBytes(StandardCharsets.UTF_8);
                int idx = 0;
                int step = 240; // ~80 chars in UTF-8 average
                while (idx < bytes.length) {
                    int end = Math.min(idx + step, bytes.length);
                    String piece = new String(bytes, idx, end - idx, StandardCharsets.UTF_8);
                    emitter.send(SseEmitter.event().data(piece));
                    idx = end;
                }
                emitter.send(SseEmitter.event().data("[DONE]"));
                emitter.complete();
            } catch (Exception e) {
                try { emitter.send(SseEmitter.event().data("[ERROR] " + e.getMessage())); } catch (Exception ignored) {}
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}
