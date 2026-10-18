package com.example.weather.eval;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.example.weather.chat.AnthropicClient;
import com.example.weather.chat.AnthropicProperties;
import com.example.weather.chat.ChatQuota;
import com.example.weather.chat.WeatherTools;
import com.example.weather.db.PostgisIntegrationTest;
import com.example.weather.dto.ChatDtos;
import com.example.weather.location.LocationRepository;
import com.example.weather.service.ChatService;
import com.example.weather.service.LocationResolver;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 챗 평가 (실제 Anthropic API를 호출하므로 과금된다). 기본 테스트에서는 제외되고 직접 실행할 때만 돈다.
 * 기상청은 위치마다 결정적인 값을 주는 FakeWeatherService로 대체하고, 채점은 EvalScorer가 코드로 한다.
 *
 * 실행: mvn test -Dtest=ChatEvalTest -Dtest.excludedGroups=none  (환경변수 ANTHROPIC_API_KEY 또는 저장소 루트 .env)
 * 환경변수: EVAL_LABEL(결과 파일 이름), EVAL_MODEL, EVAL_SPLIT(train|test|all), EVAL_REPEAT, EVAL_MAX_TOKENS(누적 상한), EVAL_PRICE_IN/OUT($/MTok)
 */
@Tag("eval")
class ChatEvalTest extends PostgisIntegrationTest {

    @Autowired
    LocationRepository locations;
    @Autowired
    LocationResolver resolver;
    @Autowired
    ObjectMapper springMapper;
    @Autowired
    AnthropicProperties productProps;

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static String apiKey() throws Exception {
        String key = System.getenv("ANTHROPIC_API_KEY");
        if (key != null && !key.isBlank()) return key;
        Path dotenv = Path.of("..", ".env");
        if (Files.exists(dotenv)) {
            for (String line : Files.readAllLines(dotenv)) {
                if (line.startsWith("ANTHROPIC_API_KEY=")) return line.substring("ANTHROPIC_API_KEY=".length()).trim();
            }
        }
        return null;
    }

    /** 호출 횟수·토큰·실패 사유를 기록하는 클라이언트 */
    static class RecordingClient extends AnthropicClient {
        int inputTokens;
        int outputTokens;
        int calls;
        String error;
        String lastStop;

        RecordingClient(AnthropicProperties props, ObjectMapper mapper) {
            super(props, mapper);
        }

        void reset() {
            inputTokens = 0;
            outputTokens = 0;
            calls = 0;
            error = null;
            lastStop = null;
        }

        @Override
        public JsonNode create(String system, JsonNode tools, JsonNode messages) {
            calls++;
            try {
                JsonNode res = super.create(system, tools, messages);
                inputTokens += res.path("usage").path("input_tokens").asInt();
                outputTokens += res.path("usage").path("output_tokens").asInt();
                lastStop = res.path("stop_reason").asText();
                return res;
            } catch (RuntimeException e) {
                error = e.getMessage();
                throw e;
            }
        }
    }

    /** 도구 호출을 기록하는 도구 모음 */
    static class RecordingTools extends WeatherTools {
        final List<EvalScorer.ToolCall> log = new ArrayList<>();

        RecordingTools(LocationRepository l, LocationResolver r, FakeWeatherService w, ObjectMapper m) {
            super(l, r, w, m);
        }

        @Override
        public Result execute(String name, JsonNode input, Context ctx) {
            Result r = super.execute(name, input, ctx);
            log.add(new EvalScorer.ToolCall(name, input.deepCopy(), r.content(), r.isError()));
            return r;
        }
    }

    private EvalScorer.Oracle oracle() {
        return new EvalScorer.Oracle() {
            @Override
            public String gridKey(String name) {
                try {
                    var g = resolver.resolve(null, null, name).grid();
                    return g.nx() + "," + g.ny();
                } catch (IllegalArgumentException e) {
                    return "NOTFOUND";
                }
            }

            @Override
            public String ctxGridKey(EvalCase.Ctx ctx) {
                var g = resolver.resolve(ctx.lat(), ctx.lon(), null).grid();
                return g.nx() + "," + g.ny();
            }

            @Override
            public List<Double> expectedValues(String value, String key, int day) {
                if ("NOTFOUND".equals(key)) return List.of();
                String[] p = key.split(",");
                int base = FakeWeatherService.baseOf(Integer.parseInt(p[0]), Integer.parseInt(p[1]));
                List<Double> out = new ArrayList<>();
                switch (value) {
                    case "current" -> out.add(FakeWeatherService.currentTemp(base));
                    case "forecast" -> {
                        DoubleSummaryStatistics s = new DoubleSummaryStatistics();
                        for (int h = 0; h < 24; h++) s.accept(FakeWeatherService.tempAt(base, day, h));
                        out.add(s.getMin());
                        out.add(s.getMax());
                        // 도구가 주는 3시간 간격 행의 기온도 정답으로 본다 (아침 기온처럼 시간대를 묻는 질문)
                        for (int h = 0; h < 24; h += 3) out.add(FakeWeatherService.tempAt(base, day, h));
                    }
                    case "pop" -> {
                        for (int h = 0; h < 24; h += 3) out.add((double) FakeWeatherService.popAt(base, day, h));
                    }
                    default -> throw new IllegalArgumentException(value);
                }
                return out;
            }
        };
    }

    @Test
    void run() throws Exception {
        String key = apiKey();
        assumeTrue(key != null, "ANTHROPIC_API_KEY가 없어 평가를 건너뜁니다");

        String label = env("EVAL_LABEL", "haiku");
        String model = env("EVAL_MODEL", "claude-haiku-5-5");
        String split = env("EVAL_SPLIT", "train");
        int repeat = Integer.parseInt(env("EVAL_REPEAT", "1"));
        long maxTokens = Long.parseLong(env("EVAL_MAX_TOKENS", "1000000"));
        Double priceIn = model.contains("haiku-5-5") ? Double.valueOf(env("EVAL_PRICE_IN", "0.10")) : optional("EVAL_PRICE_IN");
        Double priceOut = model.contains("haiku-5-5") ? Double.valueOf(env("EVAL_PRICE_OUT", "0.50")) : optional("EVAL_PRICE_OUT");

        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        List<EvalCase> all;
        try (InputStream in = getClass().getResourceAsStream("/eval/cases.json")) {
            all = mapper.readValue(in, mapper.getTypeFactory().constructCollectionType(List.class, EvalCase.class));
        }
        EvalScorer.Oracle oracle = oracle();
        List<EvalCase> cases = all.stream().filter(c -> split.equals("all") || c.split().equals(split))
                .map(c -> withRenderedHistory(c, oracle)).toList();

        // 호출 횟수·토큰 상한 등은 제품 설정(application.yml) 그대로 두고 키·모델·하루 한도만 평가용으로 바꾼다
        AnthropicProperties props = new AnthropicProperties(key, productProps.baseUrl(), model, productProps.maxTokens(),
                productProps.connectTimeoutMs(), 30000, productProps.maxModelCalls(), productProps.maxHistory(),
                productProps.maxMessageChars(), 1_000_000, 1_000_000);
        RecordingClient client = new RecordingClient(props, springMapper);
        FakeWeatherService fake = new FakeWeatherService(resolver);
        RecordingTools tools = new RecordingTools(locations, resolver, fake, springMapper);
        ChatService service = new ChatService(client, props, tools, new ChatQuota(props), fake, springMapper);

        List<Map<String, Object>> runs = new ArrayList<>();
        long totalTokens = 0;
        boolean stopped = false;
        for (int r = 1; r <= repeat && !stopped; r++) {
            List<EvalScorer.Result> results = new ArrayList<>();
            for (EvalCase c : cases) {
                if (totalTokens > maxTokens) {
                    stopped = true;
                    break;
                }
                client.reset();
                tools.log.clear();
                long t0 = System.nanoTime();
                String answer = service.chat(new ChatDtos.ChatRequest(c.messages(),
                        c.ctx() == null ? null : c.ctx().lat(), c.ctx() == null ? null : c.ctx().lon(),
                        c.ctx() == null ? null : c.ctx().city()), "eval-user").content();
                long ms = (System.nanoTime() - t0) / 1_000_000;
                // 마지막 응답이 도구 호출이면 모델 호출 횟수 상한에 걸려 규칙 기반 답변으로 대체된 것이다
                String llmError = client.error != null ? client.error
                        : "tool_use".equals(client.lastStop) ? "모델 호출 횟수 초과로 규칙 기반 답변으로 대체됨" : null;
                var trace = new EvalScorer.Trace(answer, new ArrayList<>(tools.log), client.inputTokens, client.outputTokens,
                        client.calls, ms, llmError);
                results.add(EvalScorer.score(c, trace, oracle));
                totalTokens += client.inputTokens + client.outputTokens;
            }
            Map<String, Object> run = new LinkedHashMap<>();
            run.put("run", r);
            run.put("summary", summarize(results, priceIn, priceOut));
            run.put("results", results);
            runs.add(run);
            System.out.println("[eval] " + label + " run " + r + " " + run.get("summary"));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("label", label);
        out.put("model", model);
        out.put("split", split);
        out.put("repeat", repeat);
        out.put("stoppedByTokenBudget", stopped);
        out.put("priceInPerMTok", priceIn);
        out.put("priceOutPerMTok", priceOut);
        out.put("maxModelCalls", productProps.maxModelCalls());
        out.put("environment", "로컬 PC, 기상청은 FakeWeatherService(위치별 결정적 값), Anthropic은 실제 API, 위치 테이블은 실제 PostGIS(Testcontainers)");
        out.put("runs", runs);
        Path dir = Path.of("..", "eval", "results");
        Files.createDirectories(dir);
        Path file = dir.resolve(label + "-" + split + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json");
        Files.writeString(file, mapper.enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(out));
        System.out.println("[eval] 결과 저장: " + file.toAbsolutePath().normalize());
    }

    private static final java.util.regex.Pattern HISTORY_VALUE = java.util.regex.Pattern.compile("\\{\\{(cur|fc):([^:}]+)(?::(\\d))?\\}\\}");

    /** 이전 대화의 {{cur:위치}}, {{fc:위치:일}}를 가짜 날씨 데이터와 같은 값으로 채운다 (이전 답변이 데이터와 어긋나 모델이 재검증하는 것을 막는다). */
    private static EvalCase withRenderedHistory(EvalCase c, EvalScorer.Oracle oracle) {
        List<ChatDtos.Message> msgs = new ArrayList<>();
        for (ChatDtos.Message m : c.messages()) {
            java.util.regex.Matcher mt = HISTORY_VALUE.matcher(m.content());
            StringBuilder sb = new StringBuilder();
            while (mt.find()) {
                String key = oracle.gridKey(mt.group(2));
                String text;
                if (mt.group(1).equals("cur")) {
                    text = String.format("기온 %.1f도", oracle.expectedValues("current", key, 0).get(0));
                } else {
                    List<Double> mm = oracle.expectedValues("forecast", key, Integer.parseInt(mt.group(3)));
                    text = String.format("최저 %.1f도, 최고 %.1f도", mm.get(0), mm.get(1));
                }
                mt.appendReplacement(sb, text);
            }
            mt.appendTail(sb);
            msgs.add(new ChatDtos.Message(m.role(), sb.toString()));
        }
        return new EvalCase(c.id(), c.category(), c.split(), msgs, c.ctx(), c.expect());
    }

    private static Double optional(String name) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? null : Double.valueOf(v);
    }

    static Map<String, Object> summarize(List<EvalScorer.Result> results, Double priceIn, Double priceOut) {
        Map<String, Object> s = new LinkedHashMap<>();
        long passed = results.stream().filter(EvalScorer.Result::pass).count();
        s.put("cases", results.size());
        s.put("passed", passed);
        s.put("passRate", results.isEmpty() ? null : round(100.0 * passed / results.size()));

        Map<String, double[]> checks = new TreeMap<>();
        Map<String, double[]> cats = new TreeMap<>();
        for (EvalScorer.Result r : results) {
            r.checks().forEach((k, v) -> {
                double[] a = checks.computeIfAbsent(k, x -> new double[2]);
                a[1]++;
                if (v) a[0]++;
            });
            double[] c = cats.computeIfAbsent(r.category(), x -> new double[2]);
            c[1]++;
            if (r.pass()) c[0]++;
        }
        s.put("byCheck", rates(checks));
        s.put("byCategory", rates(cats));

        List<Long> lat = results.stream().map(r -> r.trace().millis()).sorted().toList();
        if (!lat.isEmpty()) {
            s.put("latencyMsP50", lat.get((int) Math.floor((lat.size() - 1) * 0.50)));
            s.put("latencyMsP95", lat.get((int) Math.floor((lat.size() - 1) * 0.95)));
        }
        long in = results.stream().mapToLong(r -> r.trace().inputTokens()).sum();
        long out = results.stream().mapToLong(r -> r.trace().outputTokens()).sum();
        s.put("inputTokens", in);
        s.put("outputTokens", out);
        if (!results.isEmpty()) {
            s.put("avgInputTokensPerQuestion", in / results.size());
            s.put("avgOutputTokensPerQuestion", out / results.size());
        }
        if (priceIn != null && priceOut != null) {
            double usd = in * priceIn / 1e6 + out * priceOut / 1e6;
            s.put("costUsd", Math.round(usd * 1e5) / 1e5);
            if (!results.isEmpty()) s.put("costUsdPerQuestion", Math.round(usd / results.size() * 1e6) / 1e6);
        }
        if (!results.isEmpty()) {
            s.put("avgModelCalls", Math.round(results.stream().mapToInt(r -> r.trace().modelCalls()).average().orElse(0) * 100) / 100.0);
            s.put("avgToolCalls", Math.round(results.stream().mapToInt(r -> r.trace().calls().size()).average().orElse(0) * 100) / 100.0);
        }
        s.put("failedIds", results.stream().filter(r -> !r.pass()).map(EvalScorer.Result::id).toList());
        return s;
    }

    private static Map<String, String> rates(Map<String, double[]> m) {
        Map<String, String> out = new LinkedHashMap<>();
        m.forEach((k, a) -> out.put(k, (long) a[0] + "/" + (long) a[1]));
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
