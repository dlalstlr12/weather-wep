package com.example.weather.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public class ChatDtos {
    public record Message(
            @NotBlank String role,
            @NotBlank String content
    ) {}

    public record ChatRequest(
            @NotNull List<Message> messages
    ) {}

    public record ChatResponse(
            String content
    ) {}
}
