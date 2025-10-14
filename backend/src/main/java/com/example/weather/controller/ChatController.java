package com.example.weather.controller;

import com.example.weather.dto.ChatDtos;
import com.example.weather.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping
    public ResponseEntity<ChatDtos.ChatResponse> chat(@Valid @RequestBody ChatDtos.ChatRequest req) {
        return ResponseEntity.ok(chatService.chat(req));
    }
}
