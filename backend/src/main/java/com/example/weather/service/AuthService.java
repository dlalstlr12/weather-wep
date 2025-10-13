package com.example.weather.service;

import com.example.weather.dto.AuthDtos;
import com.example.weather.entity.User;
import com.example.weather.repository.UserRepository;
import com.example.weather.security.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;

    @Transactional
    public void signup(AuthDtos.SignupRequest req) {
        if (userRepository.existsByEmail(req.email())) {
            throw new IllegalArgumentException("이미 가입된 이메일입니다.");
        }
        User u = new User();
        u.setEmail(req.email());
        u.setPasswordHash(passwordEncoder.encode(req.password()));
        u.setName(req.name());
        userRepository.save(u);
    }

    @Transactional(readOnly = true)
    public AuthDtos.TokenResponse login(AuthDtos.LoginRequest req) {
        User user = userRepository.findByEmail(req.email())
                .orElseThrow(() -> new IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다."));
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다.");
        }
        String token = jwtProvider.generateToken(user.getEmail(), java.util.Map.of(
                "name", user.getName()
        ));
        return new AuthDtos.TokenResponse(token);
    }
}
