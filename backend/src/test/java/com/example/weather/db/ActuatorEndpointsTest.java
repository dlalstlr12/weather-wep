package com.example.weather.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

/** 측정값은 별도 관리 포트에서만 열리고, 서비스 포트에서는 열리지 않는지 실제 서버를 띄워 확인한다. */
// 테스트에서는 측정값 내보내기가 꺼져 있으므로 켠다
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class ActuatorEndpointsTest extends PostgisIntegrationTest {

    @LocalServerPort
    int port;
    @LocalManagementPort
    int managementPort;

    private HttpResponse<String> get(int p, String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + p + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void managementPortServesHealthAndPrometheusMetrics() throws Exception {
        assertNotEquals(port, managementPort);
        var health = get(managementPort, "/actuator/health");
        assertTrue(health.body().contains("\"status\":\"UP\""), health.statusCode() + " " + health.body());

        String metrics = get(managementPort, "/actuator/prometheus").body();
        assertTrue(metrics.contains("jvm_memory_used_bytes"));
        assertTrue(metrics.contains("kma_circuit_state"), "기상청 서킷 브레이커 상태");
        assertTrue(metrics.contains("kma_cache_size"), "기상청 캐시 크기");
        assertTrue(metrics.contains("application=\"weather-backend\""), "공통 태그");
    }

    @Test
    void servicePortDoesNotExposeMetrics() throws Exception {
        int status = get(port, "/actuator/prometheus").statusCode();
        assertTrue(status == 404 || status == 401 || status == 403, "서비스 포트에서 측정값이 열려 있다: " + status);
        assertEquals(404, get(port, "/actuator/health").statusCode());
    }
}
