package com.example.weather.service;

import com.example.weather.dto.ChatDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final HuggingFaceClient hf;

    public ChatDtos.ChatResponse chat(ChatDtos.ChatRequest req) {
        String system = "너는 한국어로 답변하는 날씨 도우미야. 간결하고 친절하게, 과도한 사족 없이 대답해.";
        String history = req.messages().stream()
                .map(m -> ("user".equals(m.role()) ? "[사용자] " : "[도우미] ") + m.content())
                .collect(Collectors.joining("\n"));
        String prompt = system + "\n\n" + history + "\n\n[도우미]";
        try {
            String out = hf.generate(prompt);
            if (out == null || out.isBlank()) out = "죄송해요, 지금은 답변을 생성할 수 없어요.";
            return new ChatDtos.ChatResponse(out);
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "알 수 없는 오류";
            return new ChatDtos.ChatResponse("오류: " + msg);
        }
    }

    public void stream(ChatDtos.ChatRequest req, SseEmitter emitter) {
        String system = "너는 한국어로 답변하는 날씨 도우미야. 간결하고 친절하게, 과도한 사족 없이 대답해.";
        String history = req.messages().stream()
                .map(m -> ("user".equals(m.role()) ? "[사용자] " : "[도우미] ") + m.content())
                .collect(Collectors.joining("\n"));
        String prompt = system + "\n\n" + history + "\n\n[도우미]";
        try {
            hf.streamChat(prompt, delta -> {
                try {
                    emitter.send(SseEmitter.event().data(delta));
                } catch (Exception ignored) { }
            });
            emitter.send(SseEmitter.event().data("[DONE]"));
            emitter.complete();
        } catch (Exception e) {
            try { emitter.send(SseEmitter.event().data("[ERROR] " + e.getMessage())); } catch (Exception ignored) {}
            emitter.completeWithError(e);
        }
    }
}
