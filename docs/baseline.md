# Phase 0 기준선 (2026-10-14)

리팩토링 전의 동작을 테스트로 고정하고, 기상청 장애 시나리오에서 현재 서비스가 어떻게 동작하는지 측정했다.
이후 단계(타임아웃·재시도·서킷 브레이커·캐시 개선)의 전후 비교 기준이다.

## 측정 환경
- 한 대의 PC(Windows 11)에서 모든 구성요소를 실행했다. 절대 성능이 아니라 **개선 전후 비교용**이다.
- 백엔드: Java 17.0.13, Spring Boot 3.3.3, `java -jar` 로컬 실행 (Tomcat 최대 스레드 50, accept-count 100), `kma.provider=data`
- DB: MySQL 8.0.43 (Docker)
- 기상청 대역: WireMock 3.9.1 standalone (`--container-threads 300 --async-response-enabled=true --async-response-threads 200`)
  - 기본 설정(컨테이너 스레드 10)에서는 지연 응답 시 WireMock 자체가 병목이 되어 첫 측정값이 왜곡됐다. 설정을 올려 다시 측정했다.
- 부하: k6 (grafana/k6 도커 이미지), 호출은 `GET /api/weather/current?lat=..&lon=..&nocache=true` (캐시 우회)
- 각 시나리오 1회 실행. 반복 측정이 아니므로 값의 분산은 알 수 없다.
- `probe`: 날씨와 무관한 가벼운 엔드포인트(`/`)를 0.5초마다 호출해 서버가 다른 요청을 계속 받는지 본다 (정상 응답은 403/404).

## 결과

| 시나리오 | 부하 | 요청 수(load) | 클라이언트가 받은 응답 | load 지연 ms (avg / med / p95 / max) | probe 지연 ms (med / p95) |
|---|---|---|---|---|---|
| 정상 (업스트림 즉시 응답) | 20 VU, 20초 | 55,393 | 200 | 7 / 6 / 14 / 70 | 5 / 14 |
| 업스트림 HTTP 500 | 20 VU, 20초 | 10,313 | **403**, 실패 100% | 39 / 38 / 71 / 145 | 1 / 4 |
| 업스트림 resultCode 오류(22) | 20 VU, 20초 | 10,550 | **403**, 실패 100% | 38 / 37 / 69 / 142 | 1 / 3 |
| 업스트림 깨진 JSON | 20 VU, 20초 | 10,349 | **403**, 실패 100% | 38 / 37 / 70 / 145 | 1 / 3 |
| 업스트림 응답 5초 지연 | 60 VU, 30초 | 310 | 200 | 5,754 / 5,019 / 9,995 / 10,000 | **4,504 / 4,516** |
| 같은 키 동시 50요청(캐시 비어 있음), 업스트림 1초 지연 | 50 VU, 1회씩 | 50 | 200 | 1,015 / 1,011 / 1,024 / 1,036 | - |

- 정상 시나리오 실패 16건과 지연 시나리오 실패 2건은 원인을 확인하지 못했다. 정상 시나리오에서는 실행마다 VU 수와 비슷한 건수(16-20건)가 나와, 부하 도구 쪽 연결 문제로 의심되지만 검증하지는 않았다.
- 업스트림 호출 수는 요청 수와 같다(HTTP 500 시나리오의 첫 실행: load 요청 9,951건, WireMock 호출 9,951건). 재시도가 없다는 뜻이다.
- 5초 지연 시나리오의 처리량은 약 10 req/s로, 스레드 50개가 5초씩 업스트림을 기다리는 것과 일치한다 (50 / 5초).

## 이 측정으로 확인된 문제
1. **업스트림 장애가 클라이언트에는 403(빈 본문)으로 나간다.** 500, resultCode 오류, 깨진 JSON 모두 동일하다. 서버 로그에는 원인 예외가 남지만, 응답을 받는 쪽은 인증 문제와 구분할 수 없다. `SecurityConfig`가 `anyRequest().authenticated()`이고 `/error`를 허용하지 않은 것이 원인으로 보인다(설정 기준 추정).
2. **타임아웃이 없다.** 업스트림이 느리면 요청 스레드가 그만큼 묶인다. 5초 지연 때 Tomcat 스레드 50개가 모두 대기에 들어가 날씨와 무관한 `/` 요청도 중앙값 4.5초가 걸렸다(정상 시 5ms).
3. **재시도와 서킷 브레이커가 없다.** 장애 중에도 요청마다 업스트림을 호출한다.
4. **캐시가 동시 요청을 합치지 않는다.** 캐시가 빈 같은 키에 50개 요청이 동시에 오면 업스트림이 50번 호출된다(이상적으로는 1번).
5. **만료된 캐시로 대신 응답하지 않는다.** 기상청이 실패하면 캐시 TTL(10분) 이후에는 그대로 오류가 된다(코드 기준; 이 측정에서 직접 재현하지는 않음).

## 테스트 작성 중 발견한 코드 결함 (현재 동작을 테스트로 고정함)
- `WeatherService`: `city`가 비어 있지 않으면 부산이 아닌 모든 도시가 서울 좌표(60,127)로 조회된다. (`WeatherServiceCharacterizationTest.anyOtherCityNameIsTreatedAsSeoul`)
- `KmaClient.skyCodeToText(null)`이 `NullPointerException`을 일으킨다. 실제 초단기실황(data.go.kr) 응답에는 SKY가 없어 `kma.provider=data`에서는 현재 날씨 조회가 실패한다. 배포 설정은 `hub`라 단기예보 응답을 써서 드러나지 않았다. (`currentWithoutSkyCategoryThrowsNullPointer`)
- 기상청 `resultCode` 오류가 `KMA 응답 파싱 실패`로 다시 감싸져 오류 코드가 메시지에서 사라진다. (`resultCodeErrorIsWrappedAsParseFailure`)
- `ForecastSelector`는 `ZoneId.systemDefault()`, `KmaClient`는 `Asia/Seoul`을 쓴다. 서버 시간대가 다르면 날짜가 어긋날 수 있다(코드 기준 추정, 미재현).

## 정리한 것
- 사용하지 않던 `spring-ai-starter-model-huggingface` 의존성 제거
- DB 비밀번호·JWT 시크릿의 코드 내 기본값 제거 (미설정 시 기동 실패), `docker-compose`의 JWT 기본값 제거, `.env.example` 추가
  - **배포 환경(Render)에 `JWT_SECRET`, `SPRING_DATASOURCE_*`가 설정돼 있어야 이 변경 후에도 기동된다.**

## 재현 방법
`perf/README.md`, `perf/run-baseline.sh` 참고. 원본 수치는 `perf/results/baseline-*.json`.
