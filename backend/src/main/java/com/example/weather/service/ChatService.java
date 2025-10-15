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
        String inferredCity = inferCity(req.city(), lastUser);
        boolean wantForecast = containsAny(lastUser, "예보","내일","모레","주간","forecast");
        boolean wantCurrent = containsAny(lastUser, "현재","지금","오늘","now","current");
        if ((wantForecast || wantCurrent) && inferredCity == null && req.lat() == null && req.lon() == null) {
            return new ChatDtos.ChatResponse("도움을 드리려면 대략적인 위치(예: 서울)와 시점(현재/내일/주간)을 알려주세요.");
        }

        if (wantCurrent || wantForecast) {
            try {
                if (wantCurrent) {
                    Double lat = req.lat(), lon = req.lon();
                    if ((lat == null || lon == null) && inferredCity != null) {
                        double[] c = geocode(inferredCity);
                        if (c != null) { lat = c[0]; lon = c[1]; }
                    }
                    WeatherDtos.CurrentWeatherResponse cw = weatherService.getCurrent(lat, lon, inferredCity, false);
                    String out = formatCurrentNlg(cw, inferredCity);
                    out = sanitizeOutput(out);
                    if (out != null && !out.isBlank()) return new ChatDtos.ChatResponse(out);
                } else {
                    Double lat = req.lat(), lon = req.lon();
                    if ((lat == null || lon == null) && inferredCity != null) {
                        double[] c = geocode(inferredCity);
                        if (c != null) { lat = c[0]; lon = c[1]; }
                    }
                    WeatherDtos.ForecastResponse fr = weatherService.getForecast(lat, lon, inferredCity, false);
                    String out = formatForecastNlg(fr, inferredCity, lastUser);
                    out = sanitizeOutput(out);
                    if (out != null && !out.isBlank()) return new ChatDtos.ChatResponse(out);
                }
            } catch (Exception ignored) { }
        }

        String prompt = buildPromptWithTools(req);
        try {
            String out = hf.generate(prompt);
            out = sanitizeOutput(out);
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
        String inferredCity = inferCity(req.city(), lastUser);
        boolean wantForecast = containsAny(lastUser, "예보","내일","모레","주간","forecast");
        boolean wantCurrent = containsAny(lastUser, "현재","지금","오늘","now","current");
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
                        double[] c = geocode(inferredCity);
                        if (c != null) { lat = c[0]; lon = c[1]; }
                    }
                    WeatherDtos.CurrentWeatherResponse cw = weatherService.getCurrent(lat, lon, inferredCity, false);
                    out = formatCurrentNlg(cw, inferredCity);
                } else {
                    Double lat = req.lat(), lon = req.lon();
                    if ((lat == null || lon == null) && inferredCity != null) {
                        double[] c = geocode(inferredCity);
                        if (c != null) { lat = c[0]; lon = c[1]; }
                    }
                    WeatherDtos.ForecastResponse fr = weatherService.getForecast(lat, lon, inferredCity, false);
                    out = formatForecastNlg(fr, inferredCity, lastUser);
                }
                out = sanitizeOutput(out);
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
                    String cleaned = sanitizeOutput(buf.toString());
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

        boolean wantForecast = containsAny(lastUser, "예보","내일","모레","주간","forecast");
        boolean wantCurrent = containsAny(lastUser, "현재","지금","오늘","now","current");

        // 간단 도시명 추출 (좌표/명시적 city가 없는 경우 보조)
        String inferredCity = inferCity(req.city(), lastUser);
        StringBuilder ctx = new StringBuilder();
        if (wantForecast || wantCurrent) {
            try {
                if (wantForecast) {
                    Double lat0 = req.lat(), lon0 = req.lon();
                    if ((lat0 == null || lon0 == null) && inferredCity != null) {
                        double[] c = geocode(inferredCity);
                        if (c != null) { lat0 = c[0]; lon0 = c[1]; }
                    }
                    WeatherDtos.ForecastResponse fr = weatherService.getForecast(lat0, lon0, inferredCity, false);
                    int idx = chooseForecastIndex(fr, lastUser);
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
                        double[] c = geocode(inferredCity);
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

    private static String inferCity(String explicitCity, String lastUser) {
        if (explicitCity != null && !explicitCity.isBlank()) return explicitCity;
        if (lastUser == null) return null;
        String t = lastUser.toLowerCase(java.util.Locale.ROOT);
        if (t.contains("서울") || t.contains("seoul")) return "서울";
        if (t.contains("부산") || t.contains("busan")) return "부산";
        if (t.contains("인천") || t.contains("incheon")) return "인천";
        if (t.contains("대구") || t.contains("daegu")) return "대구";
        if (t.contains("대전") || t.contains("daejeon")) return "대전";
        if (t.contains("광주") || t.contains("gwangju")) return "광주";
        if (t.contains("울산") || t.contains("ulsan")) return "울산";
        if (t.contains("수원") || t.contains("suwon")) return "수원";
        if (t.contains("창원") || t.contains("changwon")) return "창원";
        if (t.contains("고양") || t.contains("goyang")) return "고양";
        if (t.contains("용인") || t.contains("yongin")) return "용인";
        return null;
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

    private static String sanitizeOutput(String s) {
        if (s == null) return null;
        String out = s;
        // Remove fenced code blocks and backticks
        out = out.replaceAll("```[\\s\\S]*?```", "");
        out = out.replace("```", "");
        out = out.replace("`", "");
        // Drop common code tokens lines
        out = out.replaceAll("(?m)^.*\\b(from |import |def |class )\\b.*$", "");
        // Remove internal headers if leaked
        out = out.replaceAll("(?m)^(현재 요약:|예보 요약:|참고 요약|지시사항:|데이터 오류:|추가 정보:).*$", "");
        // Remove common English words likely to leak
        out = out.replaceAll("(?i)\\b(today|tomorrow|forecast|weather)\\b", "");
        // Collapse multiple spaces/newlines
        out = out.replaceAll("[ ]{2,}", " ");
        out = out.replaceAll("\n{3,}", "\n\n");
        out = out.trim();
        // If starts with a lone particle like '는/은/이/가', drop it
        if (out.matches("^[는은이가].*")) {
            out = out.substring(1).trim();
        }
        // Remove leading punctuation
        out = out.replaceAll("^[.,;:~!]+", "");
        return out;
    }

    private static String formatCurrentNlg(WeatherDtos.CurrentWeatherResponse cw, String city) {
        if (cw == null) return null;
        String loc = city != null && !city.isBlank() ? city : "해당 지역";
        String sky = naturalSky(nz(cw.sky()));
        String temp = nz(cw.temperature());
        String pcp = nz(cw.precipitation());
        String[] templates = new String[]{
                "지금 %s%s %s이고 기온은 %s°C, 강수는 %smm입니다.",
                "%s의 현재 하늘은 %s이며 기온 %s°C, 강수 %smm입니다.",
                "현재 %s%s %s입니다. 기온 %s°C, 강수 %smm입니다."
        };
        String fmt = choose(templates, loc + temp + pcp);
        StringBuilder sb = new StringBuilder(String.format(fmt, loc, josa(loc, "은는"), sky, temp, pcp));
        String advice = buildAdviceCurrent(temp, pcp, sky);
        if (!advice.isBlank()) sb.append(" ").append(advice);
        return sb.toString();
    }

    private static String formatForecastNlg(WeatherDtos.ForecastResponse fr, String city, String query) {
        if (fr == null || fr.items() == null || fr.items().isEmpty()) return null;
        int idx = chooseForecastIndex(fr, query);
        if (idx < 0 || idx >= fr.items().size()) idx = 0;
        var it = fr.items().get(idx);
        String loc = city != null && !city.isBlank() ? city : "해당 지역";
        String sky = naturalSky(nz(it.sky()));
        String temp = nz(it.temperature());
        String pop = nz(it.pop());
        String[] templates = new String[]{
                "예보에 따르면 %s%s %s%s 예상되고 기온은 %s°C, 강수확률은 %s%%입니다.",
                "%s의 예보는 %s%s 예상되며 기온 %s°C, 강수확률 %s%%입니다.",
                "예상되는 %s의 하늘은 %s%s, 기온 %s°C, 강수확률 %s%%입니다."
        };
        String fmt = choose(templates, loc + temp + pop);
        StringBuilder sb = new StringBuilder(String.format(fmt,
                loc, josa(loc, "은는"), sky, josa(sky, "이가"), temp, pop));
        String advice = buildAdviceForecast(temp, pop, sky);
        if (!advice.isBlank()) sb.append(" ").append(advice);
        return sb.toString();
    }

    // 간단 한국어 조사 처리: token 마지막 글자의 받침 유무로 은/는, 이/가, 을/를 선택
    private static String josa(String token, String type) {
        if (token == null || token.isBlank()) return "";
        char last = token.charAt(token.length() - 1);
        // 한글 범위: 0xAC00 ~ 0xD7A3
        boolean hasBatchim = (last >= 0xAC00 && last <= 0xD7A3) && (((last - 0xAC00) % 28) != 0);
        switch (type) {
            case "은는": return hasBatchim ? "은" : "는";
            case "이가": return hasBatchim ? "이" : "가";
            case "을를": return hasBatchim ? "을" : "를";
            default: return "";
        }
    }

    private static String naturalSky(String skyRaw) {
        String s = skyRaw.toLowerCase(java.util.Locale.ROOT).trim();
        if (s.isEmpty() || s.equals("-")) return "맑음";
        if (s.contains("clear") || s.contains("sunny") || s.contains("맑")) return "맑음";
        if (s.contains("partly") || s.contains("구름") || s.contains("cloud")) return "구름 많음";
        if (s.contains("overcast") || s.contains("흐림") || s.equals("흐림")) return "흐림";
        if (s.contains("rain") || s.contains("비")) return "비";
        if (s.contains("snow") || s.contains("눈")) return "눈";
        return skyRaw; // 그대로 반환
    }

    private static String choose(String[] arr, String seed) {
        if (arr == null || arr.length == 0) return "";
        int h = seed != null ? Math.abs(seed.hashCode()) : 0;
        return arr[h % arr.length];
    }

    private static String buildAdviceCurrent(String tempStr, String pcpStr, String sky) {
        double temp = parseDoubleSafe(tempStr, Double.NaN);
        double pcp = parseDoubleSafe(pcpStr, 0.0);
        if (pcp > 0.0 || (sky != null && sky.contains("비"))) return "우산을 챙기면 좋아요.";
        if (!Double.isNaN(temp)) {
            if (temp <= 0) return "두꺼운 외투와 장갑을 준비하세요.";
            if (temp <= 5) return "겉옷을 꼭 챙기세요.";
            if (temp >= 30) return "더위에 유의하고 수분을 충분히 섭취하세요.";
            if (temp >= 26) return "가벼운 옷차림이 좋아요.";
        }
        return "";
    }

    private static String buildAdviceForecast(String tempStr, String popStr, String sky) {
        double temp = parseDoubleSafe(tempStr, Double.NaN);
        double pop = parseDoubleSafe(popStr, Double.NaN);
        if (!Double.isNaN(pop)) {
            if (pop >= 60) return "비 가능성이 높으니 우산을 준비하세요.";
            if (pop >= 30) return "소나기에 대비해 가벼운 우산을 고려하세요.";
        }
        if (!Double.isNaN(temp)) {
            if (temp <= 0) return "매우 춥겠습니다. 보온에 유의하세요.";
            if (temp <= 5) return "쌀쌀하니 겉옷을 챙기세요.";
            if (temp >= 30) return "더위에 유의하고 수분 섭취를 잊지 마세요.";
            if (temp >= 26) return "덥겠습니다. 가벼운 옷차림이 좋아요.";
        }
        if (sky != null && sky.contains("눈")) return "눈길 안전에 유의하세요.";
        return "";
    }

    private static double parseDoubleSafe(String s, double def) {
        if (s == null) return def;
        try { return Double.parseDouble(s); } catch (Exception e) { return def; }
    }

    // ----- Geocoding (basic map for KR major cities) -----
    private static double[] geocode(String city) {
        if (city == null) return null;
        String c = city.trim().toLowerCase(java.util.Locale.ROOT);
        switch (c) {
            case "서울": case "seoul": return new double[]{37.5665, 126.9780};
            case "부산": case "busan": return new double[]{35.1796, 129.0756};
            case "대구": case "daegu": return new double[]{35.8714, 128.6014};
            case "인천": case "incheon": return new double[]{37.4563, 126.7052};
            case "광주": case "gwangju": return new double[]{35.1595, 126.8526};
            case "대전": case "daejeon": return new double[]{36.3504, 127.3845};
            case "울산": case "ulsan": return new double[]{35.5384, 129.3114};
            case "수원": case "suwon": return new double[]{37.2636, 127.0286};
            case "창원": case "changwon": return new double[]{35.2283, 128.6811};
            case "고양": case "goyang": return new double[]{37.6584, 126.8320};
            case "용인": case "yongin": return new double[]{37.2411, 127.1775};
            default: return null;
        }
    }

    // ----- Intent parsing and forecast slot chooser -----
    private static int chooseForecastIndex(WeatherDtos.ForecastResponse fr, String query) {
        if (fr == null || fr.items() == null || fr.items().isEmpty()) return -1;
        Intent intent = parseIntent(query);
        java.time.LocalDate baseDate = java.time.LocalDate.now(java.time.ZoneId.systemDefault()).plusDays(intent.dayOffset);
        Integer targetHour = targetHourForWindow(intent.window);
        int bestIdx = -1;
        long bestScore = Long.MAX_VALUE;
        for (int i = 0; i < fr.items().size(); i++) {
            var it = fr.items().get(i);
            java.time.LocalDateTime dt = parseDateTimeSafe(String.valueOf(it.dateTime()));
            if (dt == null) continue;
            if (!dt.toLocalDate().isEqual(baseDate)) continue;
            long score = 0;
            if (targetHour != null) {
                score = Math.abs(dt.getHour() - targetHour);
            } else {
                // midday default
                score = Math.abs(dt.getHour() - 12);
            }
            // prefer higher POP when tie
            try {
                long negPop = -Math.round(Double.parseDouble(String.valueOf(it.pop())));
                score = score * 1000 + (negPop + 1000); // stable
            } catch (Exception ignored) { }
            if (score < bestScore) { bestScore = score; bestIdx = i; }
        }
        if (bestIdx == -1) {
            // fallback: pick first
            bestIdx = 0;
        }
        return bestIdx;
    }

    private static class Intent {
        int dayOffset; // 0 today, 1 tomorrow, 2 day after
        String window; // morning/afternoon/evening/night/any
    }

    private static Intent parseIntent(String text) {
        Intent in = new Intent();
        in.dayOffset = 0; in.window = "any";
        if (text == null) return in;
        String t = text.toLowerCase(java.util.Locale.ROOT);
        if (t.contains("모레")) in.dayOffset = 2;
        else if (t.contains("내일") || t.contains("tomorrow")) in.dayOffset = 1;
        else if (t.contains("주간") || t.contains("이번 주") || t.contains("한 주")) in.dayOffset = 1; // weekly -> start from tomorrow
        if (t.contains("오전") || t.contains("morning")) in.window = "morning";
        else if (t.contains("오후") || t.contains("afternoon")) in.window = "afternoon";
        else if (t.contains("저녁") || t.contains("evening")) in.window = "evening";
        else if (t.contains("밤") || t.contains("night")) in.window = "night";
        return in;
    }

    private static Integer targetHourForWindow(String window) {
        if (window == null) return null;
        switch (window) {
            case "morning": return 9;
            case "afternoon": return 15;
            case "evening": return 19;
            case "night": return 22;
            default: return null;
        }
    }

    private static java.time.LocalDateTime parseDateTimeSafe(String s) {
        if (s == null) return null;
        String[] patterns = new String[]{
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm",
                "yyyy-MM-dd'T'HH:mm",
                "yyyyMMddHHmm",
                "yyyyMMddHH"
        };
        for (String p : patterns) {
            try {
                java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern(p);
                return java.time.LocalDateTime.parse(s, f);
            } catch (Exception ignored) { }
        }
        try { return java.time.LocalDateTime.parse(s); } catch (Exception ignored) { }
        return null;
    }
}
