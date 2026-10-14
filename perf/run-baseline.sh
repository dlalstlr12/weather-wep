#!/usr/bin/env bash
# 기준선 측정: WireMock(8089), 백엔드(8080)가 이미 떠 있다고 가정한다. README 참고.
# 사용법: bash perf/run-baseline.sh <label> [시나리오 하나만]   (결과: perf/results/<label>-<scenario>.json)
# WireMock은 지연 응답 때문에 스레드가 부족하지 않도록 --container-threads 300 --async-response-enabled=true --async-response-threads 200 로 띄운다.
set -u
LABEL="${1:-baseline}"
ONLY="${2:-}"
cd "$(dirname "$0")/.."
mkdir -p perf/results

k6run() { # name, wiremock scenario, extra env...
  local name="$1" wm="$2"; shift 2
  [ -z "$ONLY" ] || [ "$ONLY" = "$name" ] || return 0
  python perf/setup_wiremock.py "$wm" > /dev/null
  echo "=== $LABEL-$name (wiremock=$wm) ==="
  MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W 2>/dev/null || pwd)/perf:/perf" "$@" \
    grafana/k6 run --quiet --summary-export="/perf/results/$LABEL-$name.json" /perf/k6/scenario.js 2>&1 \
    | grep -E "http_req_duration\{ scenario|http_req_failed\{ scenario|http_reqs\.|iterations\."
  echo "upstream calls: $(curl -s -X POST http://localhost:8089/__admin/requests/count -H 'Content-Type: application/json' -d '{"method":"GET"}')"
}

k6run ok          ok       -e VUS=20 -e DURATION=20s
k6run http500     http500  -e VUS=20 -e DURATION=20s
k6run resultcode  resultcode -e VUS=20 -e DURATION=20s
k6run malformed   malformed -e VUS=20 -e DURATION=20s
k6run delay5s     delay5s  -e VUS=60 -e DURATION=30s
# 캐시가 빈 같은 키를 50명이 동시에 호출: 업스트림이 몇 번 호출되는가 (캐시 키: 격자+발표시각).
# 좌표는 실행마다 무작위로 정해 이전 실행의 10분 캐시에 걸리지 않게 한다.
k6run stampede    delay1s  -e MODE=stampede -e VUS=50 -e "QS=lat=$((30 + RANDOM % 8)).$((RANDOM % 9))&lon=$((126 + RANDOM % 3)).$((RANDOM % 9))"
