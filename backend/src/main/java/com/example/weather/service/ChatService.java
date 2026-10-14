package com.example.weather.service;

import com.example.weather.dto.ChatDtos;
import com.example.weather.dto.WeatherDtos;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 챗 응답. 날씨 질문은 데이터로 바로 답하고(LLM 호출 없음), 데이터를 못 가져왔거나 날씨 질문이 아니면 LLM에 넘긴다.
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final String ASK_LOCATION = "도움을 드리려면 대략적인 위치(예: 서울)와 시점(현재/내일/주간)을 알려주세요.";

    private static final String SYSTEM_PROMPT = String.join("\n",
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

    private final HuggingFaceClient hf;
    private final WeatherService weatherService;

    /** 질문에서 읽어낸 의도. 현재와 예보 키워드가 함께 있으면 현재를 우선한다. */
    private record Question(String text, String city, Double lat, Double lon, boolean current, boolean forecast) {
        static Question from(ChatDtos.ChatRequest req) {
            String text = req.messages().stream()
                    .filter(m -> "user".equalsIgnoreCase(m.role()))
                    .reduce((a, b) -> b)
                    .map(ChatDtos.Message::content)
                    .orElse("");
            return new Question(
                    text,
                    IntentParser.inferCity(req.city(), text),
                    req.lat(), req.lon(),
                    IntentParser.containsAny(text, "현재", "지금", "오늘", "now", "current"),
                    IntentParser.containsAny(text, "예보", "내일", "모레", "주간", "forecast"));
        }

        boolean asksWeather() {
            return current || forecast;
        }

        boolean lacksLocation() {
            return asksWeather() && city == null && lat == null && lon == null;
        }
    }

    /** 날씨 조회 결과. 실패하면 failed=true. */
    private record Lookup(Question q, WeatherDtos.CurrentWeatherResponse current,
                          WeatherDtos.ForecastResponse forecast, int forecastIndex, boolean failed) {

        String answer() {
            if (failed) return null;
            return current != null
                    ? NlgFormatter.formatCurrent(current, q.city())
                    : NlgFormatter.formatForecast(forecast, q.city(), q.text(), forecastIndex);
        }

        /** LLM 프롬프트에 넣을 한 줄 요약 */
        String summary() {
            if (failed) return "데이터 오류: 날씨 데이터를 불러오지 못했습니다.";
            if (current != null) {
                return String.format(Locale.ROOT, "현재 요약: 기온 %s°C, 강수 %smm, 하늘 %s.",
                        nz(current.temperature()), nz(current.precipitation()), nz(current.sky()));
            }
            if (forecastIndex >= 0 && forecastIndex < forecast.items().size()) {
                var it = forecast.items().get(forecastIndex);
                return String.format(Locale.ROOT, "예보 요약: %s 기준, 기온 %s°C, 하늘 %s, 강수확률 %s%%.",
                        nz(it.dateTime()), nz(it.temperature()), nz(it.sky()), nz(it.pop()));
            }
            return "";
        }
    }

    public ChatDtos.ChatResponse chat(ChatDtos.ChatRequest req) {
        Question q = Question.from(req);
        if (q.lacksLocation()) {
            return new ChatDtos.ChatResponse(ASK_LOCATION);
        }

        Lookup lookup = q.asksWeather() ? lookup(q) : null;
        if (lookup != null) {
            String out = TextSanitizer.sanitize(lookup.answer());
            if (out != null && !out.isBlank()) return new ChatDtos.ChatResponse(out);
        }

        try {
            String out = TextSanitizer.sanitize(hf.generate(buildPrompt(q, lookup)));
            if (out == null || out.isBlank()) out = "죄송해요, 지금은 답변을 생성할 수 없어요.";
            return new ChatDtos.ChatResponse(out);
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "알 수 없는 오류";
            return new ChatDtos.ChatResponse("오류: " + msg);
        }
    }

    private Lookup lookup(Question q) {
        try {
            if (q.current()) {
                return new Lookup(q, weatherService.getCurrent(q.lat(), q.lon(), q.city(), false), null, -1, false);
            }
            WeatherDtos.ForecastResponse fr = weatherService.getForecast(q.lat(), q.lon(), q.city(), false);
            return new Lookup(q, null, fr, ForecastSelector.chooseIndex(fr, q.text()), false);
        } catch (Exception e) {
            return new Lookup(q, null, null, -1, true);
        }
    }

    private static String buildPrompt(Question q, Lookup lookup) {
        String summary = lookup == null ? "" : lookup.summary();
        String system = summary.isBlank() ? SYSTEM_PROMPT : SYSTEM_PROMPT + "\n\n참고 요약(출력에 인용 금지): " + summary;
        return system + "\n\n" + q.text();
    }

    private static String nz(Object v) {
        return v == null ? "-" : String.valueOf(v);
    }
}
