package com.example.weather.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.weather.dto.ChatDtos;
import com.example.weather.dto.WeatherDtos;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** ChatService의 현재 분기 동작(결정적 경로 → LLM 경로)을 고정하는 특성화 테스트. */
class ChatServiceCharacterizationTest {

    private HuggingFaceClient hf;
    private WeatherService weather;
    private ChatService chat;

    @BeforeEach
    void setUp() {
        hf = mock(HuggingFaceClient.class);
        weather = mock(WeatherService.class);
        chat = new ChatService(hf, weather);
    }

    private static ChatDtos.ChatRequest ask(String text) {
        return new ChatDtos.ChatRequest(List.of(new ChatDtos.Message("user", text)), null, null, null);
    }

    @Test
    void weatherQuestionWithoutLocationAsksForItAndSkipsLlm() {
        ChatDtos.ChatResponse r = chat.chat(ask("지금 날씨 어때?"));
        assertTrue(r.content().startsWith("도움을 드리려면"));
        verifyNoInteractions(hf);
        verifyNoInteractions(weather);
    }

    @Test
    void currentWeatherForKnownCityAnswersDeterministicallyWithoutLlm() {
        when(weather.getCurrent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("서울"), org.mockito.ArgumentMatchers.eq(false)))
                .thenReturn(new WeatherDtos.CurrentWeatherResponse(18.5, 0.0, "맑음"));
        ChatDtos.ChatResponse r = chat.chat(ask("서울 지금 날씨 알려줘"));
        assertTrue(r.content().contains("서울"));
        assertTrue(r.content().contains("18.5°C"));
        verifyNoInteractions(hf);
    }

    @Test
    void forecastForKnownCityAnswersDeterministicallyWithoutLlm() {
        var fr = new WeatherDtos.ForecastResponse(List.of(
                new WeatherDtos.ForecastEntry("202610141200", 20.0, 0.0, "맑음", 10, 50, 1.0)));
        when(weather.getForecast(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("부산"), org.mockito.ArgumentMatchers.eq(false))).thenReturn(fr);
        ChatDtos.ChatResponse r = chat.chat(ask("부산 예보 알려줘"));
        assertTrue(r.content().contains("부산"));
        verifyNoInteractions(hf);
    }

    @Test
    void weatherServiceFailureFallsThroughToLlmWithErrorNoteInPrompt() {
        when(weather.getCurrent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                anyString(), org.mockito.ArgumentMatchers.anyBoolean())).thenThrow(new RuntimeException("KMA down"));
        when(hf.generate(anyString())).thenReturn("지금은 날씨를 확인할 수 없어요.");
        ChatDtos.ChatResponse r = chat.chat(ask("서울 지금 날씨"));
        assertEquals("지금은 날씨를 확인할 수 없어요.", r.content());
        verify(hf).generate(org.mockito.ArgumentMatchers.contains("데이터 오류"));
    }

    @Test
    void nonWeatherQuestionGoesToLlm() {
        when(hf.generate(anyString())).thenReturn("안녕하세요");
        assertEquals("안녕하세요", chat.chat(ask("안녕")).content());
    }

    @Test
    void llmFailureIsReturnedAsErrorTextNotException() {
        when(hf.generate(anyString())).thenThrow(new RuntimeException("model unavailable"));
        assertEquals("오류: model unavailable", chat.chat(ask("안녕")).content());
    }

    @Test
    void blankLlmOutputBecomesApology() {
        when(hf.generate(anyString())).thenReturn("   ");
        assertEquals("죄송해요, 지금은 답변을 생성할 수 없어요.", chat.chat(ask("안녕")).content());
    }
}
