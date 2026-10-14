-- 위치 검색용 확장과 행정구역 위치 테이블
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 기상청 동네예보 행정구역별 격자 좌표. 데이터는 이후 마이그레이션에서 적재한다.
CREATE TABLE locations (
  id            BIGSERIAL PRIMARY KEY,
  region_code   VARCHAR(20),
  sido          VARCHAR(40) NOT NULL,
  sigungu       VARCHAR(40),
  eupmyeondong  VARCHAR(40),
  full_name     VARCHAR(130) NOT NULL,
  nx            INT NOT NULL,
  ny            INT NOT NULL,
  geom          GEOGRAPHY(Point, 4326) NOT NULL
);

-- 반경 검색(ST_DWithin)과 가장 가까운 지역(KNN, <->)을 위한 공간 인덱스
CREATE INDEX idx_locations_geom ON locations USING GIST (geom);
-- 이름 부분 일치 검색(ILIKE '%역삼%')용
CREATE INDEX idx_locations_full_name_trgm ON locations USING GIN (full_name gin_trgm_ops);
CREATE INDEX idx_locations_grid ON locations (nx, ny);
