// 기상청 장애 시나리오 측정용 k6 스크립트.
// load : 날씨 API를 VUS명이 DURATION 동안 호출
// probe: 날씨와 무관한 가벼운 엔드포인트("/")를 0.5초마다 호출해 서버가 다른 요청을 계속 받는지 확인
// stampede 모드: 같은 (캐시 비어 있는) 키를 VUS명이 동시에 1번씩 호출
import http from 'k6/http';
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';

const staleResponses = new Counter('stale_responses');
const okResponses = new Counter('ok_responses');

const BASE = __ENV.BASE || 'http://host.docker.internal:8080';
const MODE = __ENV.MODE || 'load';
const VUS = parseInt(__ENV.VUS || '20');
const DURATION = __ENV.DURATION || '20s';
const QS = __ENV.QS || 'lat=37.5665&lon=126.9780&nocache=true';

const scenarios = MODE === 'stampede'
  ? { load: { executor: 'shared-iterations', vus: VUS, iterations: VUS, maxDuration: '60s', exec: 'load' } }
  : {
      load: { executor: 'constant-vus', vus: VUS, duration: DURATION, exec: 'load' },
      probe: { executor: 'constant-vus', vus: 1, duration: DURATION, exec: 'probe' },
    };

export const options = {
  scenarios,
  // 서브메트릭을 요약에 포함시키기 위한 항목 (임계값은 판정에 쓰지 않음)
  thresholds: {
    'http_req_duration{scenario:load}': ['max>=0'],
    'http_req_duration{scenario:probe}': ['max>=0'],
    'http_req_failed{scenario:load}': ['rate>=0'],
    'http_req_failed{scenario:probe}': ['rate>=0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'max'],
};

export function load() {
  const r = http.get(`${BASE}/api/weather/current?${QS}`, { timeout: '120s' });
  if (r.status === 200) {
    okResponses.add(1);
    try { if (r.json('stale') === true) staleResponses.add(1); } catch (e) { /* 본문이 JSON이 아니면 무시 */ }
  }
}

export function probe() {
  http.get(`${BASE}/`, { timeout: '120s', responseCallback: http.expectedStatuses(403, 404) });
  sleep(0.5);
}
