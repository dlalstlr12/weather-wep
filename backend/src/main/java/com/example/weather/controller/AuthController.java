package com.example.weather.controller;

import com.example.weather.dto.AuthDtos;
import com.example.weather.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/signup")
    public ResponseEntity<Void> signup(@Valid @RequestBody AuthDtos.SignupRequest req) {
        authService.signup(req);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/login")
    public ResponseEntity<AuthDtos.TokenResponse> login(@Valid @RequestBody AuthDtos.LoginRequest req) {
        return ResponseEntity.ok(authService.login(req));
    }
}
