package com.example.weather.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public class AuthDtos {
    public record SignupRequest(
            @NotBlank String name,
            @NotBlank @Email String email,
            @NotBlank String password
    ) {}

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {}

    public record TokenResponse(String accessToken) {}
}
