# Weather WEP

직관적인 날씨 조회와 지도 탐색, 간단한 날씨 대화(챗)를 제공하는 풀스택 프로젝트입니다. 프론트는 Cloudflare Pages, 백엔드는 Render, 데이터베이스는 Railway(MySQL)로 배포되었습니다. 포트폴리오 목적의 실전형 CI/CD 및 멀티 플랫폼 배포 구성을 포함합니다.

## 라이브 링크
- 프론트엔드: https://weather-wep.pages.dev
- 백엔드 API: https://weather-wep.onrender.com

## 한눈에 보기
- 프론트엔드: React + Vite + TypeScript
- 백엔드: Spring Boot 3 (Java 17), Spring Security + JWT, JPA(Hibernate), Flyway
- 데이터베이스: MySQL (Railway)
- 인프라/배포: Cloudflare Pages(프론트), Render(백), Railway(DB)
- 기타: Docker/Docker Compose, Jenkins(선택적), Kakao 지도 SDK, KMA(기상청) API 연동 설계

## 핵심 기능
- 현재 위치/지도 선택/도시 검색 기반의 현재 날씨 + 단기 예보 조회
- 즐겨찾기 도시 관리 및 지도 마커 표시
- 로그인/회원가입(JWT) 및 로그인 상태 기반의 보호 라우트(날씨챗)
- 간단한 날씨 대화(챗) UI (백엔드 챗 엔드포인트 연동)

---

# 아키텍처
```
Cloudflare Pages(React)  ──(HTTPS, CORS)──>  Render(Spring Boot API)  ──>  Railway(MySQL)
                                     └── 외부 API(KMA 등)
```
- 프론트는 `VITE_API_BASE_URL`을 통해 백엔드 절대 URL로 요청
- 백엔드는 CORS에서 Cloudflare Pages 도메인 허용, JWT 기반 인증 적용

## 모노레포 구조
```
root
├─ frontend/              # React(Vite) 앱
│  ├─ src/
│  └─ Dockerfile
├─ backend/               # Spring Boot 앱
│  ├─ src/main/java
│  ├─ src/main/resources
│  ├─ pom.xml
│  └─ Dockerfile
├─ docker-compose.yml     # 로컬 통합 실행
├─ Jenkinsfile            # (선택) Jenkins 파이프라인
└─ README.md
```

---

# 실행 방법
## 1) 로컬 (Docker Compose)
1. 환경변수 준비
   - 프론트(.env):
     - `VITE_API_BASE_URL=http://localhost:8080`
     - `VITE_KAKAO_MAPS_KEY=카카오_Javascript_키`
   - 백엔드(application.yml 또는 env):
     - `SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/weather_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8`
     - `SPRING_DATASOURCE_USERNAME=...`
     - `SPRING_DATASOURCE_PASSWORD=...`
     - `JWT_SECRET=임의의_랜덤_시크릿`
     - (옵션) `HF_API_TOKEN`, `KMA_SERVICE_KEY` 등
2. 실행: `docker compose up --build`
3. 접속: http://localhost (프론트), http://localhost:8080 (백엔드)

## 2) 배포 환경
- 프론트: Cloudflare Pages (환경변수 `VITE_API_BASE_URL`, `VITE_KAKAO_MAPS_KEY` 설정 후 빌드)
- 백엔드: Render (CORS 허용 오리진에 Pages 도메인 추가, Flyway 마이그레이션 자동 실행)
- DB: Railway(MySQL)

---

# 환경 변수
## 프론트엔드
- `VITE_API_BASE_URL`: 백엔드 API 베이스 URL (예: `https://weather-wep.onrender.com`)
- `VITE_KAKAO_MAPS_KEY`: Kakao 지도 JavaScript 키 (Kakao Developers에 배포 도메인 허용 등록 필요)

## 백엔드
- `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`
- `JWT_SECRET`
- (옵션) `HF_API_TOKEN` (모델 연동 시), `KMA_SERVICE_KEY`, `PORT`(호스팅 제공자에 의해 주입)

---

# 보안 및 CORS
- Spring Security + JWT
  - 공개: `/`, `/actuator/**`, `OPTIONS /**`, `GET /api/weather/**`, `/api/auth/**`
  - 보호: 그 외 모든 API (특히 `/api/chat/**`)
- CORS 허용 오리진: `https://weather-wep.pages.dev`, `http://localhost:5173`

---

# 데이터 모델(ERD 개요)
> 실제 엔티티 명/컬럼은 마이그레이션(Flyway) 및 코드 기준으로 운영되며, 아래는 포트폴리오 설명용 개요입니다.

```mermaid
erDiagram
    USER ||--o{ REFRESH_TOKEN : has
    USER {
      BIGINT id PK
      VARCHAR email UNIQUE
      VARCHAR password_hash
      VARCHAR name
      DATETIME created_at
    }
    REFRESH_TOKEN {
      BIGINT id PK
      BIGINT user_id FK
      VARCHAR token
      DATETIME expires_at
    }
    WEATHER_CACHE {
      BIGINT id PK
      VARCHAR location_key
      TEXT payload_json
      DATETIME cached_at
      DATETIME expires_at
    }
```

---

# API 명세(요약)
Base URL: `${VITE_API_BASE_URL}` (프론트) / `https://weather-wep.onrender.com` (배포)

## 인증
- POST `/api/auth/signup`
  - req: `{ email, password, name }`
  - res: `{ id, email, name }`
- POST `/api/auth/login`
  - req: `{ email, password }`
  - res: `{ accessToken }`

## 날씨
- GET `/api/weather/current?lat=..&lon=..` 또는 `?city=...`
  - res: `{ temperature, sky, precipitation, ... }`
- GET `/api/weather/forecast?lat=..&lon=..` 또는 `?city=...`
  - res: `{ items: [ { dateTime, temperature, sky, precipitation, ... }, ... ] }`

## 날씨 챗(보호)
- POST `/api/chat/message`
  - headers: `Authorization: Bearer <token>`
  - req: `{ message: string }`
  - res: `{ reply: string }`

> 실제 응답 스키마는 구현에 따라 일부 차이가 있을 수 있습니다. 프론트 소스의 호출 형식을 기준으로 사용 가능합니다.

---

# 기능 명세(요약)
- 메인
  - 현재 위치/지도 선택/도시 검색 → 현재 날씨 + 예보 표시
  - 즐겨찾기 추가/선택 → 마커/정보창
- 인증
  - 회원가입/로그인(JWT), 로그인 상태 반영(새로고침 없이 라우트 가드 즉시 갱신)
- 날씨챗
  - 보호 라우트(`/chat`), 간단 Q&A 인터페이스

---

# CI/CD
- 현재 구성
  - 프론트: Cloudflare Pages Git 연동 자동 빌드/배포
  - 백엔드: Render Git 연동 자동 배포(또는 Deploy Hook)
  - DB: Railway MySQL (마이그레이션은 백엔드 기동 시 Flyway 수행)
- 선택(확장): Jenkins 파이프라인에서 테스트→빌드→배포(각 플랫폼 API/CLI 호출) 중앙 관리 가능

---

# 트러블슈팅 메모
- CORS: 프론트 배포 도메인을 백엔드 CORS 허용에 추가, 프리플라이트 `OPTIONS` 공개
- Kakao 지도: Kakao Developers에 배포 도메인(및 프리뷰 도메인) 등록, `VITE_KAKAO_MAPS_KEY` 환경변수 설정
- API Base URL: 끝에 슬래시 없이 설정 권장 (이중 슬래시 방지)

---

# 개발 로그(요약 타임라인)
1. 프로젝트 스캐폴딩(React/Vite, Spring Boot), 기본 화면/엔드포인트 구성
2. 날씨 API 연동(현재/예보), UI 컴포넌트(차트/리스트/아이콘) 작성
3. 인증(JWT) 및 보호 라우트 적용, 로그인 상태 즉시 반영 로직 구현
4. Kakao 지도 연동: 위치/검색/마커, 즐겨찾기 표시
5. Docker/Docker Compose로 로컬 통합, Flyway 도입
6. 배포: Cloudflare Pages(프론트), Render(백엔드), Railway(MySQL)
7. CORS/프리플라이트, Kakao 도메인 허용 문제 해결
8. 최종 폴리싱 및 문서화(README/명세)

---

# 라이선스
본 저장소는 포트폴리오 목적의 예시 프로젝트로, 별도의 라이선스 명시가 없는 한 개인 학습 및 시연 용도로 사용 가능합니다.
