package com.example.weather.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.time.Clock;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** 재시도와 서킷 브레이커 동작. 기상청은 WireMock으로 대체한다. */
class KmaResilienceTest {

    private static final String ULTRA = "/getUltraSrtNcst";

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    @BeforeEach
    void reset() {
        wm.resetAll();
    }

    private KmaClient client(int retryAttempts, int breakerWindow, int breakerMinCalls, long breakerOpenMs) {
        KmaProperties props = KmaFixtures.props(wm.baseUrl(), new KmaProperties.Http(500, 1000),
                new KmaProperties.Cache(600, 10800, 1000),
                new KmaProperties.Resilience(retryAttempts, 10, breakerWindow, breakerMinCalls, 50f, breakerOpenMs));
        return new KmaClient(props, Clock.system(ZoneId.of("Asia/Seoul")));
    }

    private void call(KmaClient c) {
        c.getUltraNowcast(37.5665, 126.9780, true);
    }

    private void stubFirstThenOk(int firstStatus, String firstBody) {
        var first = aResponse().withStatus(firstStatus);
        if (firstBody != null) first.withHeader("Content-Type", "application/json").withBody(firstBody);
        wm.stubFor(get(urlPathEqualTo(ULTRA)).inScenario("retry").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(first).willSetStateTo("recovered"));
        wm.stubFor(get(urlPathEqualTo(ULTRA)).inScenario("retry").whenScenarioStateIs("recovered")
                .willReturn(okJson(KmaFixtures.ULTRA_OK)));
    }

    @Test
    void transientServerErrorIsRetriedAndSucceeds() {
        stubFirstThenOk(503, null);
        call(client(2, 100, 100, 1000));
        wm.verify(2, getRequestedFor(urlPathEqualTo(ULTRA)));
    }

    @Test
    void gatewayTimeoutResultCode05IsRetried() {
        stubFirstThenOk(200, KmaFixtures.resultCode("05", "SERVICETIMEOUT_ERROR"));
        call(client(2, 100, 100, 1000));
        wm.verify(2, getRequestedFor(urlPathEqualTo(ULTRA)));
    }

    @Test
    void retryStopsAfterTheConfiguredNumberOfAttempts() {
        wm.stubFor(get(urlPathEqualTo(ULTRA)).willReturn(aResponse().withStatus(500)));
        KmaApiException e = assertThrows(KmaApiException.class, () -> call(client(3, 100, 100, 1000)));
        assertEquals(KmaApiException.Kind.HTTP_ERROR, e.kind());
        wm.verify(3, getRequestedFor(urlPathEqualTo(ULTRA)));
    }

    @Test
    void clientErrorsAreNotRetried() {
        wm.stubFor(get(urlPathEqualTo(ULTRA)).willReturn(aResponse().withStatus(400)));
        assertThrows(KmaApiException.class, () -> call(client(3, 100, 100, 1000)));
        wm.verify(1, getRequestedFor(urlPathEqualTo(ULTRA)));
    }

    @Test
    void quotaExceededIsNotRetried() {
        wm.stubFor(get(urlPathEqualTo(ULTRA)).willReturn(okJson(KmaFixtures.RESULT_CODE_ERROR)));
        assertThrows(KmaApiException.class, () -> call(client(3, 100, 100, 1000)));
        wm.verify(1, getRequestedFor(urlPathEqualTo(ULTRA)));
    }

    @Test
    void timeoutIsRetried() {
        wm.stubFor(get(urlPathEqualTo(ULTRA)).inScenario("slow").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(KmaFixtures.ULTRA_OK).withFixedDelay(2000)).willSetStateTo("fast"));
        wm.stubFor(get(urlPathEqualTo(ULTRA)).inScenario("slow").whenScenarioStateIs("fast")
                .willReturn(okJson(KmaFixtures.ULTRA_OK)));
        call(client(2, 100, 100, 1000)); // 첫 시도는 읽기 타임아웃(1초)에 끊기고 두 번째가 성공한다
        wm.verify(2, getRequestedFor(urlPathEqualTo(ULTRA)));
    }

    @Test
    void breakerOpensAfterRepeatedFailuresAndStopsCallingUpstream() {
        wm.stubFor(get(urlPathEqualTo(ULTRA)).willReturn(aResponse().withStatus(500)));
        KmaClient c = client(1, 4, 4, 5000);
        for (int i = 0; i < 4; i++) assertThrows(KmaApiException.class, () -> call(c));
        KmaApiException e = assertThrows(KmaApiException.class, () -> call(c));
        assertEquals(KmaApiException.Kind.CIRCUIT_OPEN, e.kind());
        wm.verify(4, getRequestedFor(urlPathEqualTo(ULTRA))); // 열린 뒤에는 업스트림 호출이 늘지 않는다
    }

    @Test
    void breakerClosesAgainAfterTheUpstreamRecovers() throws Exception {
        wm.stubFor(get(urlPathEqualTo(ULTRA)).willReturn(aResponse().withStatus(500)));
        KmaClient c = client(1, 4, 4, 300);
        for (int i = 0; i < 4; i++) assertThrows(KmaApiException.class, () -> call(c));
        assertEquals(KmaApiException.Kind.CIRCUIT_OPEN,
                assertThrows(KmaApiException.class, () -> call(c)).kind());

        wm.stubFor(get(urlPathEqualTo(ULTRA)).willReturn(okJson(KmaFixtures.ULTRA_OK)));
        Thread.sleep(400); // 대기 시간이 지나면 반열림 상태에서 시험 호출을 허용한다
        call(c);
        call(c);
    }

    @Test
    void clientSideErrorsDoNotOpenTheBreaker() {
        wm.stubFor(get(urlPathEqualTo(ULTRA)).willReturn(aResponse().withStatus(400)));
        KmaClient c = client(1, 4, 4, 5000);
        for (int i = 0; i < 8; i++) {
            KmaApiException e = assertThrows(KmaApiException.class, () -> call(c));
            assertEquals(KmaApiException.Kind.HTTP_ERROR, e.kind());
        }
        wm.verify(8, getRequestedFor(urlPathEqualTo(ULTRA)));
    }

    @Test
    void noDataIsNotCountedAsAnUpstreamFailure() {
        wm.stubFor(get(urlPathEqualTo(ULTRA)).willReturn(okJson(KmaFixtures.resultCode("03", "NODATA_ERROR"))));
        KmaClient c = client(1, 4, 4, 5000);
        for (int i = 0; i < 8; i++) {
            KmaApiException e = assertThrows(KmaApiException.class, () -> call(c));
            assertEquals(KmaApiException.Kind.NO_DATA, e.kind());
        }
        // 호출마다 현재 슬롯과 바로 앞 슬롯, 2번 조회하므로 8번 호출 = 16 요청. 브레이커는 열리지 않아 모두 업스트림까지 간다
        wm.verify(16, getRequestedFor(urlPathEqualTo(ULTRA)));
    }
}
