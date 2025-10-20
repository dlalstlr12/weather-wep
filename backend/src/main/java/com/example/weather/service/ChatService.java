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
        // deterministic fallback: if insufficient info, do not call LLM
        String lastUser = req.messages().stream()
                .filter(m -> "user".equalsIgnoreCase(m.role()))
                .reduce((a,b) -> b)
                .map(ChatDtos.Message::content)
                .orElse("");
        String inferredCity = IntentParser.inferCity(req.city(), lastUser);
        boolean wantForecast = IntentParser.containsAny(lastUser, "예보","내일","모레","주간","forecast");
        boolean wantCurrent = IntentParser.containsAny(lastUser, "현재","지금","오늘","now","current");
        if ((wantForecast || wantCurrent) && inferredCity == null && req.lat() == null && req.lon() == null) {
            return new ChatDtos.ChatResponse("도움을 드리려면 대략적인 위치(예: 서울)와 시점(현재/내일/주간)을 알려주세요.");
        }

        if (wantCurrent || wantForecast) {
            try {
                if (wantCurrent) {
                    Double lat = req.lat(), lon = req.lon();
                    if ((lat == null || lon == null) && inferredCity != null) {
                        double[] c = GeoService.geocode(inferredCity);
                        if (c != null) { lat = c[0]; lon = c[1]; }
                    }
                    WeatherDtos.CurrentWeatherResponse cw = weatherService.getCurrent(lat, lon, inferredCity, false);
                    String out = NlgFormatter.formatCurrent(cw, inferredCity);
                    out = TextSanitizer.sanitize(out);
                    if (out != null && !out.isBlank()) return new ChatDtos.ChatResponse(out);
                } else {
                    Double lat = req.lat(), lon = req.lon();
                    if ((lat == null || lon == null) && inferredCity != null) {
                        double[] c = GeoService.geocode(inferredCity);
                        if (c != null) { lat = c[0]; lon = c[1]; }
                    }
                    WeatherDtos.ForecastResponse fr = weatherService.getForecast(lat, lon, inferredCity, false);
                    int idx = ForecastSelector.chooseIndex(fr, lastUser);
                    String out = NlgFormatter.formatForecast(fr, inferredCity, lastUser, idx);
                    out = TextSanitizer.sanitize(out);
                    if (out != null && !out.isBlank()) return new ChatDtos.ChatResponse(out);
                }
            } catch (Exception ignored) { }
        }

        String prompt = buildPromptWithTools(req);
        try {
            String out = hf.generate(prompt);
            out = TextSanitizer.sanitize(out);
            if (out == null || out.isBlank()) out = "죄송해요, 지금은 답변을 생성할 수 없어요.";
            return new ChatDtos.ChatResponse(out);
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "알 수 없는 오류";
            return new ChatDtos.ChatResponse("오류: " + msg);
        }
    }

    public void stream(ChatDtos.ChatRequest req, SseEmitter emitter) {
        String lastUser = req.messages().stream()
                .filter(m -> "user".equalsIgnoreCase(m.role()))
                .reduce((a,b) -> b)
                .map(ChatDtos.Message::content)
                .orElse("");
        String inferredCity = IntentParser.inferCity(req.city(), lastUser);
        boolean wantForecast = IntentParser.containsAny(lastUser, "예보","내일","모레","주간","forecast");
        boolean wantCurrent = IntentParser.containsAny(lastUser, "현재","지금","오늘","now","current");
        if ((wantForecast || wantCurrent) && inferredCity == null && req.lat() == null && req.lon() == null) {
            try {
                emitter.send(SseEmitter.event().data("도움을 드리려면 대략적인 위치(예: 서울)와 시점(현재/내일/주간)을 알려주세요."));
                emitter.send(SseEmitter.event().data("[DONE]"));
                emitter.complete();
                return;
            } catch (Exception e) {
                try { emitter.send(SseEmitter.event().data("[ERROR] " + e.getMessage())); } catch (Exception ignored) {}
                emitter.completeWithError(e);
                return;
            }
        }

        if (wantCurrent || wantForecast) {
            try {
                String out;
                if (wantCurrent) {
                    Double lat = req.lat(), lon = req.lon();
                    if ((lat == null || lon == null) && inferredCity != null) {
                        double[] c = GeoService.geocode(inferredCity);
                        if (c != null) { lat = c[0]; lon = c[1]; }
                    }
                    WeatherDtos.CurrentWeatherResponse cw = weatherService.getCurrent(lat, lon, inferredCity, false);
                    out = NlgFormatter.formatCurrent(cw, inferredCity);
                } else {
                    Double lat = req.lat(), lon = req.lon();
                    if ((lat == null || lon == null) && inferredCity != null) {
                        double[] c = GeoService.geocode(inferredCity);
                        if (c != null) { lat = c[0]; lon = c[1]; }
                    }
                    WeatherDtos.ForecastResponse fr = weatherService.getForecast(lat, lon, inferredCity, false);
                    int idx = ForecastSelector.chooseIndex(fr, lastUser);
                    out = NlgFormatter.formatForecast(fr, inferredCity, lastUser, idx);
                }
                out = TextSanitizer.sanitize(out);
                if (out != null && !out.isBlank()) {
                    emitter.send(SseEmitter.event().data(out));
                    emitter.send(SseEmitter.event().data("[DONE]"));
                    emitter.complete();
                    return;
                }
            } catch (Exception ignored) { }
        }
        String prompt = buildPromptWithTools(req);
        try {
            final StringBuilder buf = new StringBuilder();
            final int[] sent = new int[]{0};
            hf.streamChat(prompt, delta -> {
                try {
                    if (delta == null) return;
                    buf.append(delta);
                    String cleaned = TextSanitizer.sanitize(buf.toString());
                    if (cleaned == null) return;
                    if (cleaned.length() > sent[0]) {
                        String inc = cleaned.substring(sent[0]);
                        sent[0] = cleaned.length();
                        if (!inc.isBlank()) {
                            emitter.send(SseEmitter.event().data(inc));
                        }
                    }
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
        String systemBase = String.join("\n",
                "너는 한국어로만 답변하는 날씨 도우미야.",
                "- 항상 사실에 근거해 간결하게 답변해.",
                "- 영어를 섞지 말고, 한국어만 사용해.",
                "- 불확실하면 명확히 밝혀.",
                "- 과도한 특수문자나 반복 기호(>, = 등)를 사용하지 마.",
                "- 최대 4문장 이내로 답변해.",
                "- 요약은 한두 문장으로 날씨 상태·기온·강수 가능성을 핵심만 전달하고, 필요 시 1문장 조언을 덧붙여라.",
                "- 온도는 °C, 강수는 mm, 확률은 % 단위를 사용하고 숫자와 단위를 붙여 표기한다(예: 23°C, 60%).",
                "- 아래 참고 데이터나 지시 문구(예: '현재 데이터:', '예보 데이터:', '다음은 참고용 데이터다')를 그대로 반복하거나 인용하지 말고, 최종 답만 출력하라.",
                "- 질문으로 되묻지 말고, 답만 출력하라.");
        // keep only the last user content to minimize echo
        String history = req.messages().stream()
                .filter(m -> "user".equalsIgnoreCase(m.role()))
                .reduce((a,b) -> b)
                .map(m -> m.content())
                .orElse("");

        String lastUser = req.messages().stream()
                .filter(m -> "user".equalsIgnoreCase(m.role()))
                .reduce((a,b) -> b)
                .map(ChatDtos.Message::content)
                .orElse("");

        boolean wantForecast = IntentParser.containsAny(lastUser, "예보","내일","모레","주간","forecast");
        boolean wantCurrent = IntentParser.containsAny(lastUser, "현재","지금","오늘","now","current");

        // 간단 도시명 추출 (좌표/명시적 city가 없는 경우 보조)
        String inferredCity = IntentParser.inferCity(req.city(), lastUser);
        StringBuilder ctx = new StringBuilder();
        if (wantForecast || wantCurrent) {
            try {
                if (wantForecast) {
                    Double lat0 = req.lat(), lon0 = req.lon();
                    if ((lat0 == null || lon0 == null) && inferredCity != null) {
                        double[] c = GeoService.geocode(inferredCity);
                        if (c != null) { lat0 = c[0]; lon0 = c[1]; }
                    }
                    WeatherDtos.ForecastResponse fr = weatherService.getForecast(lat0, lon0, inferredCity, false);
                    int idx = ForecastSelector.chooseIndex(fr, lastUser);
                    if (idx >= 0 && idx < fr.items().size()) {
                        var it = fr.items().get(idx);
                        ctx.append("예보 요약: ");
                        ctx.append(String.format(Locale.ROOT,
                                "%s 기준, 기온 %s°C, 하늘 %s, 강수확률 %s%%.",
                                nz(it.dateTime()), nz(it.temperature()), nz(it.sky()), nz(it.pop())));
                    }
                } else {
                    Double lat0 = req.lat(), lon0 = req.lon();
                    if ((lat0 == null || lon0 == null) && inferredCity != null) {
                        double[] c = GeoService.geocode(inferredCity);
                        if (c != null) { lat0 = c[0]; lon0 = c[1]; }
                    }
                    WeatherDtos.CurrentWeatherResponse cw = weatherService.getCurrent(lat0, lon0, inferredCity, false);
                    ctx.append(String.format(Locale.ROOT,
                            "현재 요약: 기온 %s°C, 강수 %smm, 하늘 %s.",
                            nz(cw.temperature()), nz(cw.precipitation()), nz(cw.sky())));
                }
            } catch (Exception e) {
                ctx.append("데이터 오류: 날씨 데이터를 불러오지 못했습니다.");
            }
        }
        // merge guidance and tool data into system to avoid echo
        String system = systemBase + (ctx.length() > 0 ? ("\n\n참고 요약(출력에 인용 금지): " + ctx) : "");
        String prompt = system + "\n\n" + history;
        return prompt;
    }

    private static String nz(Object v) {
        return v == null ? "-" : String.valueOf(v);
    }
}
