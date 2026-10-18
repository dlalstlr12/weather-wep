# 문서 목록

단계별 설계·측정·평가 기록이다. 모든 수치에는 측정 환경과 한계가 같이 적혀 있다. 처음 보면 아래 순서를 권한다.

| 문서 | 내용 | 핵심 |
|---|---|---|
| [baseline.md](baseline.md) | Phase 0 기준선: 테스트로 현재 동작을 고정하고 기상청 장애 시나리오를 측정 | 장애가 403으로 나감, 5초 지연이면 스레드 50개 대기(무관한 요청 4.5초), 동시 요청 50개가 호출 50번 |
| [phase1-structure.md](phase1-structure.md) | Phase 1 구조 리팩토링 | `KmaClient` 303줄 → 91줄 + 단계별 클래스, 오류 응답 통일(400/502/503) |
| [phase2-resilience.md](phase2-resilience.md) | Phase 2 외부 API 안정성 | 타임아웃·재시도·서킷 브레이커·캐시, 무관한 요청 4.5초 → 2ms, 동시 50요청 → 호출 1회 |
| [phase3-postgis.md](phase3-postgis.md) | Phase 3 PostgreSQL + PostGIS | 행정구역 3,836곳, 공간 인덱스 21.7~131.7배, 배포 전환 절차, 데이터 설명 |
| [phase4-llm-chat.md](phase4-llm-chat.md) | Phase 4 도구 호출 챗 | Claude Haiku 5.5 도구 3개, 비용 상한, 규칙 기반 폴백, 실호출 7건 확인 |
| [phase5-eval.md](phase5-eval.md) | Phase 5 챗 평가 | 50문항, 코드 채점, 시험셋 평균 84.4%, 평가가 찾은 문제와 채점 기준을 고친 이력 |
| [phase6-observability.md](phase6-observability.md) | Phase 6 관측성·CI·커버리지 | 측정값 11종, Prometheus+Grafana, 장애 주입 검증, JaCoCo 90.0%, GitHub Actions |
| [troubleshooting.md](troubleshooting.md) | 겪은 문제와 해결 모음 | 측정 방법의 실수, 서비스 결함, 챗 문제, 프론트 문제 |

## 문서 밖의 근거 자료
- `perf/` — 장애 주입·PostGIS 벤치마크 스크립트와 원본 결과(JSON), 실행 방법은 `perf/README.md`
- `eval/results/` — 챗 평가의 질문별 원본 결과(도구 호출, 답변, 토큰, 지연, 실패 사유)
- `observability/grafana/dashboards/weather-backend.json` — 대시보드 정의, 스크린샷은 `docs/images/`
- `backend/src/test/resources/eval/cases.json` — 평가셋 50문항

## 수치를 읽을 때
- 성능 수치는 로컬 PC 한 대, 기상청은 WireMock 대역에서 잰 값이다. 운영 환경의 값이 아니다.
- 챗 평가의 날씨는 가짜 데이터이고, 시험셋(15문항)은 마지막 개선 뒤 미공개 상태가 아니다.
- 비용은 모델 카드의 단가(입력 $0.10, 출력 $0.50 per MTok)에 측정한 토큰을 곱한 계산값이다.
