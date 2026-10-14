#!/usr/bin/env bash
# 시나리오마다 백엔드를 새로 띄워 서킷 브레이커·캐시 상태가 섞이지 않게 측정한다 (Windows git-bash 기준).
# 전제: WireMock(8089)과 DB(PostgreSQL)가 떠 있고, SPRING_DATASOURCE_*, JWT_SECRET, KMA_SERVICE_KEY 환경변수가 export 되어 있다.
# 사용법: bash perf/run-isolated.sh <label> [백엔드에 넘길 추가 인자...]
set -u
LABEL="${1:?label}"; shift
EXTRA_ARGS=("$@")
cd "$(dirname "$0")/.."

stop_backend() {
  local pid
  pid=$(netstat -ano | grep ":8080 " | grep LISTENING | awk '{print $5}' | head -1)
  [ -n "$pid" ] && taskkill //PID "$pid" //F > /dev/null 2>&1
  sleep 2
}

start_backend() {
  nohup java -jar backend/target/weather-backend-0.0.1-SNAPSHOT.jar \
    --kma.provider=data --kma.data.base-url=http://localhost:8089 "${EXTRA_ARGS[@]}" > "${TEMP:-/tmp}/backend-$LABEL.log" 2>&1 &
  for _ in $(seq 1 60); do
    code=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/ || true)
    [ "$code" != "000" ] && return 0
    sleep 1
  done
  echo "backend did not start" >&2; return 1
}

for name in ok http500 resultcode malformed delay5s stampede stale-outage; do
  stop_backend
  start_backend || exit 1
  # JVM 워밍업 요청 (측정 대상 아님)
  python perf/setup_wiremock.py ok > /dev/null
  curl -s -o /dev/null "http://localhost:8080/api/weather/current?lat=35.0&lon=127.0&nocache=true"
  bash perf/run-baseline.sh "$LABEL" "$name"
done
stop_backend
