package com.example.weather;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@SpringBootApplication
public class WeatherApplication {
    public static void main(String[] args) {
        // Load .env from working directory if present (only sets missing system properties)
        loadDotenv();
        SpringApplication.run(WeatherApplication.class, args);
    }

    private static void loadDotenv() {
        Path p = Path.of(".env");
        if (!Files.exists(p)) return;
        Map<String, String> map = new HashMap<>();
        try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim();
                if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
                    val = val.substring(1, val.length() - 1);
                }
                map.put(key, val);
            }
        } catch (IOException ignored) { }
        int applied = 0;
        for (Map.Entry<String, String> e : map.entrySet()) {
            System.setProperty(e.getKey(), e.getValue());
            applied++;
        }
        System.out.println("[dotenv] applied keys: " + applied + " (system properties overridden by .env)");
    }
}
