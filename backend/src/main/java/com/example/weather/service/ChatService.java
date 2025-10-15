package com.example.weather.service;

import com.example.weather.dto.ChatDtos;
import com.example.weather.dto.WeatherDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final HuggingFaceClient hf;
    private final WeatherService weatherService;

    public ChatDtos.ChatResponse chat(ChatDtos.ChatRequest req) {
        String prompt = buildPromptWithTools(req);
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
        String prompt = buildPromptWithTools(req);
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

    private String buildPromptWithTools(ChatDtos.ChatRequest req) {
        String system = String.join("\n",
                "너는 한국어로만 답변하는 날씨 도우미야.",
                "- 항상 사실에 근거해 간결하게 답변해.",
                "- 영어를 섞지 말고, 한국어만 사용해.",
                "- 불확실하면 명확히 밝혀.",
                "- 과도한 특수문자나 반복 기호(>, = 등)를 사용하지 마.",
                "- 최대 4문장 이내로 답변해.");
        String history = req.messages().stream()
                .map(m -> ("user".equals(m.role()) ? "[사용자] " : "[도우미] ") + m.content())
                .collect(Collectors.joining("\n"));

        String lastUser = req.messages().stream()
                .filter(m -> "user".equalsIgnoreCase(m.role()))
                .reduce((a,b) -> b)
                .map(ChatDtos.Message::content)
                .orElse("");

        boolean wantForecast = containsAny(lastUser, "예보","내일","모레","주간","forecast");
        boolean wantCurrent = containsAny(lastUser, "현재","지금","오늘","now","current");

        // 간단 도시명 추출 (좌표/명시적 city가 없는 경우 보조)
        String inferredCity = req.city();
        if (inferredCity == null || inferredCity.isBlank()) {
            String t = lastUser.toLowerCase(java.util.Locale.ROOT);
            if (t.contains("부산") || t.contains("busan")) inferredCity = "부산";
            else if (t.contains("서울") || t.contains("seoul")) inferredCity = "서울";
        }
        StringBuilder ctx = new StringBuilder();
        if (wantForecast || wantCurrent) {
            try {
                if (wantForecast) {
                    WeatherDtos.ForecastResponse fr = weatherService.getForecast(req.lat(), req.lon(), inferredCity, false);
                    ctx.append("\n[실제_예보]\n");
                    int limit = Math.min(8, fr.items().size());
                    for (int i = 0; i < limit; i++) {
                        var it = fr.items().get(i);
                        ctx.append(String.format(Locale.ROOT,
                                "%s | 기온:%s°C | 강수:%smm | 하늘:%s | 강수확률:%s%% | 습도:%s%% | 바람:%sm/s\n",
                                nz(it.dateTime()), nz(it.temperature()), nz(it.precipitation()), nz(it.sky()), nz(it.pop()), nz(it.reh()), nz(it.wsd())));
                    }
                } else {
                    WeatherDtos.CurrentWeatherResponse cw = weatherService.getCurrent(req.lat(), req.lon(), inferredCity, false);
                    ctx.append("\n[실제_현재]\n");
                    ctx.append(String.format(Locale.ROOT,
                            "기온:%s°C | 강수:%smm | 하늘:%s\n",
                            nz(cw.temperature()), nz(cw.precipitation()), nz(cw.sky())));
                }
            } catch (Exception e) {
                ctx.append("\n[도구_오류] 날씨 데이터를 불러오지 못했습니다: ").append(e.getMessage());
            }
        }

        String guide = "\n지시사항:" +
                "\n- 반드시 한국어만 사용하라. 영어 단어(cloudy, wind, variability 등)나 혼합 표기는 금지한다." +
                "\n- 위의 [실제_*] 데이터만 근거로 간결히 요약하라. 추측하거나 과장하지 말며, 없는 정보는 임의로 말하지 않는다." +
                "\n- 필요 시 우산 권장 여부, 체감 요소(강수/바람/습도/바람)를 1~2문장으로 조언하라." +
                "\n- 요약은 한 문장 또는 두 문장 이내로 작성하되, 날씨 상태·기온·강수 가능성을 핵심만 전달하라." +
                "\n- 조언은 필요할 경우에만 1~2문장으로 작성하며, 우산 여부, 체감 요소(바람/습도/강수)를 중심으로 안내하라." +
                "\n- 온도는 °C, 강수는 mm, 확률은 % 단위를 사용하고 숫자와 단위를 붙여 표기하라(예: 23°C, 60%)." +
                "\n- 특수문자나 같은 글자의 과도한 반복(>, =, ~ 등)은 사용하지 말라." +
                "\n- 필요한 데이터가 없거나 위치 정보가 모호하면 일반적인 조언 대신, “위치를 알려주세요” 등 한 문장으로 요청만 한다." +
                "\n출력 형식(예시):" +
                "\n- 요약: 내일 서울은 구름이 많고 강수확률은 20%입니다. 기온은 24°C 정도입니다." +
                "\n- 조언: 우산은 크게 필요 없지만, 소나기 가능성에 대비해 가벼운 우산을 고려하세요.";

        String prompt = system + "\n\n" + history + "\n\n[도우미]\n" +
                (ctx.length() > 0 ? ctx.toString() + guide + "\n" : "");
        return prompt;
    }

    private static boolean containsAny(String text, String... keys) {
        if (text == null) return false;
        String t = text.toLowerCase(Locale.ROOT);
        for (String k : keys) {
            if (t.contains(k.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static String nz(Object v) {
        return v == null ? "-" : String.valueOf(v);
    }
}
