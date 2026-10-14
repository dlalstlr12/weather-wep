# 성능·장애 측정 (기준선)

기상청을 WireMock으로 대체하고 백엔드를 실제로 띄운 뒤 k6로 장애 시나리오별 현재 동작을 측정한다.

## 구성
- DB: `docker run -d --name weather-baseline-db -p 5433:5432 -e POSTGRES_DB=weather_db -e POSTGRES_PASSWORD=rootpass postgis/postgis:16-3.4` (Phase 3 이후, `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/weather_db`, 계정 postgres). Phase 0-2 측정은 MySQL 8.0(Docker)로 했다
- 기상청 대역: `wiremock-standalone` jar (`java -jar ... --port 8089 --container-threads 300 --async-response-enabled=true --async-response-threads 200`), 시나리오는 `perf/setup_wiremock.py`로 설정
- 백엔드: `java -jar backend/target/weather-backend-0.0.1-SNAPSHOT.jar --kma.provider=data --kma.data.base-url=http://localhost:8089`
- 부하: `grafana/k6` 도커 이미지 (`perf/k6/scenario.js`), 결과는 `perf/results/*.json`

## 전체 실행
```bash
bash perf/run-baseline.sh <label> [시나리오]   # ok|http500|resultcode|malformed|delay5s|stampede
```

## 시나리오마다 백엔드를 새로 띄워 측정 (Phase 2 이후)
서킷 브레이커와 캐시 상태가 다음 시나리오로 넘어가면 결과가 오염되므로 시나리오마다 백엔드를 재시작한다.
```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/weather_db SPRING_DATASOURCE_USERNAME=postgres SPRING_DATASOURCE_PASSWORD=rootpass JWT_SECRET=... KMA_SERVICE_KEY=test-key
bash perf/run-isolated.sh <label> --kma.cache.ttl-seconds=2   # stale-outage 시나리오에 필요
```
시나리오: `ok http500 resultcode malformed delay5s stampede stale-outage`. 업스트림(WireMock) 호출 수는 `perf/results/<label>-<시나리오>.upstream.json`.

## 실행 예
```bash
python perf/setup_wiremock.py delay5s
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W)/perf:/perf" -e VUS=60 -e DURATION=30s \
  grafana/k6 run --summary-export=/perf/results/baseline-delay5s.json /perf/k6/scenario.js
```
측정 환경과 해석은 `docs/baseline.md` 참고.

## PostGIS 인덱스 벤치마크
`python perf/postgis/run_benchmark.py <행 수>` (합성 점), `python perf/postgis/run_real_benchmark.py` (실제 행정구역 데이터). PostGIS 컨테이너 `weather-bench` 필요, 스크립트 상단 설명 참고. 결과는 `perf/postgis/results-*.txt`. 쿼리마다 세션을 새로 열면 세션 초기화 비용이 섞이므로 같은 세션에서 반복해 측정한다.
