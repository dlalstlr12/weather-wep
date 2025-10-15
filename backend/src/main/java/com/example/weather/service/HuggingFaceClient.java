package com.example.weather.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
public class HuggingFaceClient {

    @Value("${huggingface.api-base}")
    private String apiBase;

    @Value("${huggingface.model}")
    private String model;

    @Value("${huggingface.api-token}")
    private String apiToken;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    public String generate(String prompt) {
        try {
            String base = (apiBase == null ? "https://api-inference.huggingface.co/models" : apiBase.trim()).replaceAll("/+$(?-i)", "");
            String modelId = (model == null ? "" : model.trim());
            boolean isRouterChat = base.contains("/v1/chat/completions");
            String url = isRouterChat ? base : (base + "/" + modelId);
            // debug
            try {
                String tokenInfo = (apiToken == null ? "null" : ("len=" + apiToken.trim().length()));
                System.out.println("[HF] base=" + base + ", model=" + modelId + ", token(" + tokenInfo + ")");
            } catch (Exception ignored) {}
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            headers.set("User-Agent", "weather-backend/1.0");
            if (apiToken != null && !apiToken.isBlank()) {
                headers.set("Authorization", "Bearer " + apiToken);
            }
            Map<String, Object> body = new HashMap<>();
            if (isRouterChat) {
                // OpenAI-compatible chat/completions schema
                body.put("model", modelId);
                body.put("messages", buildMessages(prompt));
                body.put("temperature", 0.2);
                body.put("max_tokens", 160);
                body.put("frequency_penalty", 0.6);
                body.put("presence_penalty", 0.0);
                body.put("top_p", 0.9);
                body.put("top_k", 40);
                body.put("stop", List.of(
                        "사용자:",
                        "도우미:",
                        "현재 요약:",
                        "예보 요약:",
                        "참고 요약",
                        "지시사항:",
                        "데이터 오류:",
                        "추가 정보:",
                        "```",
                        "```python",
                        "from ",
                        "import ",
                        "def ",
                        "class ")
                );
                body.put("stream", false);
            } else {
                // Legacy inference API schema
                body.put("inputs", prompt);
                Map<String, Object> params = new HashMap<>();
                params.put("max_new_tokens", 256);
                params.put("temperature", 0.7);
                params.put("return_full_text", false);
                body.put("parameters", params);
            }

            String json = mapper.writeValueAsString(body);
            HttpEntity<String> entity = new HttpEntity<>(json, headers);

            ResponseEntity<String> resp;
            try {
                resp = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            } catch (HttpStatusCodeException ex) {
                String respBody = ex.getResponseBodyAsString();
                String message = "HF Inference API error (" + ex.getStatusCode().value() + " " + ex.getStatusText() + ")" +
                        " | url=" + url + " | model=" + modelId;
                if (respBody != null && !respBody.isBlank()) {
                    // Try to extract 'error' field if JSON
                    try {
                        Map<String, Object> err = mapper.readValue(respBody, new TypeReference<>(){});
                        Object e2 = err.get("error");
                        if (e2 != null) message += ": " + String.valueOf(e2);
                    } catch (Exception ignored) {
                        message += ": " + respBody;
                    }
                }
                throw new RuntimeException(message);
            }
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                throw new RuntimeException("HF Inference API error: " + resp.getStatusCode() + " | url=" + url + " | model=" + modelId);
            }

            if (isRouterChat) {
                // Parse OpenAI-style response
                Map<String, Object> root = mapper.readValue(resp.getBody(), new TypeReference<>(){});
                Object err = root.get("error");
                if (err != null) throw new RuntimeException("HF Inference API error: " + err + " | url=" + url + " | model=" + modelId);
                List<Map<String, Object>> choices = (List<Map<String, Object>>) root.get("choices");
                if (choices != null && !choices.isEmpty()) {
                    Map<String, Object> first = choices.get(0);
                    Map<String, Object> message = (Map<String, Object>) first.get("message");
                    if (message != null) {
                        Object content = message.get("content");
                        if (content != null) return String.valueOf(content).trim();
                    }
                }
                // Fallback to usage of text field if present
                Object text = root.get("text");
                if (text != null) return String.valueOf(text).trim();
                return "";
            } else {
                // Parse legacy array response: [{generated_text: "..."}]
                List<Map<String, Object>> arr = mapper.readValue(resp.getBody(), new TypeReference<>(){});
                if (!arr.isEmpty()) {
                    Object gt = arr.get(0).get("generated_text");
                    if (gt != null) return String.valueOf(gt).trim();
                }
                // Some models may return dict with error
                Map<String, Object> maybeErr = null;
                try { maybeErr = mapper.readValue(resp.getBody(), new TypeReference<>(){}); } catch (Exception ignored) {}
                if (maybeErr != null && maybeErr.containsKey("error")) {
                    throw new RuntimeException("HF Inference API error: " + maybeErr.get("error"));
                }
                return "";
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void streamChat(String prompt, Consumer<String> onDelta) {
        String base = (apiBase == null ? "https://api-inference.huggingface.co/models" : apiBase.trim()).replaceAll("/+$(?-i)", "");
        String modelId = (model == null ? "" : model.trim());
        boolean isRouterChat = base.contains("/v1/chat/completions");
        if (!isRouterChat) {
            // Fallback: single-shot
            String out = generate(prompt);
            if (out != null && !out.isBlank()) onDelta.accept(out);
            return;
        }
        HttpURLConnection conn = null;
        try {
            // debug
            try {
                String tokenInfo = (apiToken == null ? "null" : ("len=" + apiToken.trim().length()));
                System.out.println("[HF-stream] base=" + base + ", model=" + modelId + ", token(" + tokenInfo + ")");
            } catch (Exception ignored) {}
            URL url = new URL(base);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("User-Agent", "weather-backend/1.0");
            if (apiToken != null && !apiToken.isBlank()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiToken);
            }

            Map<String, Object> body = new HashMap<>();
            body.put("model", modelId);
            body.put("messages", buildMessages(prompt));
            body.put("temperature", 0.2);
            body.put("max_tokens", 160);
            body.put("frequency_penalty", 0.6);
            body.put("presence_penalty", 0.0);
            body.put("top_p", 0.9);
            body.put("top_k", 40);
            body.put("stop", List.of(
                    "사용자:",
                    "도우미:",
                    "현재 요약:",
                    "예보 요약:",
                    "참고 요약",
                    "지시사항:",
                    "데이터 오류:",
                    "추가 정보:",
                    "```",
                    "```python",
                    "from ",
                    "import ",
                    "def ",
                    "class ")
            );
            body.put("stream", true);
            String json = mapper.writeValueAsString(body);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String err = null;
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line; while ((line = br.readLine()) != null) sb.append(line);
                    err = sb.toString();
                } catch (Exception ignored) {}
                throw new RuntimeException("HF Inference API error (" + code + ") | url=" + base + " | model=" + modelId + (err != null ? (": " + err) : ""));
            }

            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isBlank()) continue; // SSE frame separator
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) break;
                    try {
                        Map<String, Object> obj = mapper.readValue(data, new TypeReference<>(){});
                        List<Map<String, Object>> choices = (List<Map<String, Object>>) obj.get("choices");
                        if (choices != null && !choices.isEmpty()) {
                            Map<String, Object> first = choices.get(0);
                            Map<String, Object> delta = (Map<String, Object>) first.get("delta");
                            if (delta != null) {
                                Object content = delta.get("content");
                                if (content != null) onDelta.accept(String.valueOf(content));
                                continue;
                            }
                            Map<String, Object> message = (Map<String, Object>) first.get("message");
                            if (message != null) {
                                Object content = message.get("content");
                                if (content != null) onDelta.accept(String.valueOf(content));
                                continue;
                            }
                            Object text = first.get("text");
                            if (text != null) onDelta.accept(String.valueOf(text));
                        }
                    } catch (Exception ignored) {
                        // If not JSON, emit raw line for visibility
                        onDelta.accept(data);
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private List<Map<String, Object>> buildMessages(String prompt) {
        // First paragraph (up to first blank line) as system; rest as user
        String sys = null;
        String user = prompt;
        int cut = prompt.indexOf("\n\n");
        if (cut > 0) {
            sys = prompt.substring(0, cut).trim();
            user = prompt.substring(cut + 2).trim();
        }
        if (sys == null || sys.isBlank()) {
            return List.of(Map.of("role", "user", "content", user));
        }
        return List.of(
                Map.of("role", "system", "content", sys),
                Map.of("role", "user", "content", user)
        );
    }
}
