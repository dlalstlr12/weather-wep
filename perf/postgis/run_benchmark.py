"""PostGIS 공간 인덱스(GiST) 유무에 따른 쿼리 실행 계획·시간 비교.

실행 중인 PostGIS 컨테이너(이름 weather-bench)에 docker exec로 psql을 실행한다.
  docker run -d --name weather-bench -e POSTGRES_PASSWORD=bench -e POSTGRES_DB=bench postgis/postgis:16-3.4
  python perf/postgis/run_benchmark.py [행 수]

합성 데이터: 대한민국 경계 상자(경도 124.5-132.0, 위도 33.0-38.5) 안에 균등 분포한 무작위 점.
실제 행정구역은 도시에 몰려 있어 분포가 다르므로, 인덱스 효과의 규모를 가늠하는 용도다.
"""
import re
import statistics
import subprocess
import sys

ROWS = int(sys.argv[1]) if len(sys.argv) > 1 else 1_000_000
RUNS = 7
CONTAINER = "weather-bench"

POINT = "ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography"
QUERIES = {
    "반경 5km 검색 (ST_DWithin)": f"SELECT id FROM bench_points WHERE ST_DWithin(geom, {POINT}, 5000)",
    "가장 가까운 5개 (KNN, <->)": f"SELECT id FROM bench_points ORDER BY geom <-> {POINT} LIMIT 5",
}


def psql(sql):
    out = subprocess.run(
        ["docker", "exec", "-i", CONTAINER, "psql", "-U", "postgres", "-d", "bench", "-v", "ON_ERROR_STOP=1", "-At"],
        input=sql.encode("utf-8"), capture_output=True, check=True)
    return out.stdout.decode("utf-8", "replace")


def explain_runs(query):
    """같은 psql 세션에서 RUNS+1번 실행한다. 새 세션의 첫 호출에는 PROJ·SRID 캐시 초기화(약 8ms)가 섞이므로
    첫 번째 결과는 버리고, 연결을 재사용하는 서버(커넥션 풀)의 상태를 측정한다."""
    text = psql(";\n".join(["EXPLAIN (ANALYZE, BUFFERS) " + query] * (RUNS + 1)) + ";")
    times = [float(m) for m in re.findall(r"Execution Time: ([\d.]+) ms", text)][1:]
    plan = text.split("Planning")[0]
    node = re.findall(r"(Seq Scan|Index Scan|Bitmap Heap Scan|Bitmap Index Scan)", plan)
    return times, " / ".join(dict.fromkeys(node)), plan


def measure(label):
    print(f"\n== {label}")
    results = {}
    for name, q in QUERIES.items():
        times, node, text = explain_runs(q)
        results[name] = (statistics.median(times), min(times), node, text)
        print(f"  {name}: median {statistics.median(times):.2f} ms, min {min(times):.2f} ms, 계획 {node}")
    return results


print(psql("SELECT postgis_version(), version()").strip())
psql(f"""
DROP TABLE IF EXISTS bench_points;
CREATE TABLE bench_points (id bigserial PRIMARY KEY, geom geography(Point, 4326) NOT NULL);
INSERT INTO bench_points (geom)
  SELECT ST_SetSRID(ST_MakePoint(124.5 + random() * 7.5, 33.0 + random() * 5.5), 4326)::geography
  FROM generate_series(1, {ROWS});
ANALYZE bench_points;
""")
print(f"행 수: {ROWS:,}")

before = measure("인덱스 없음")
psql("CREATE INDEX idx_bench_geom ON bench_points USING GIST (geom); ANALYZE bench_points;")
after = measure("GiST 인덱스")

print("\n== 요약 (median ms)")
for name in QUERIES:
    b, a = before[name][0], after[name][0]
    print(f"  {name}: {b:.2f} -> {a:.2f} ms ({b / a:.0f}배), {before[name][2]} -> {after[name][2]}")

print("\n== 실행 계획 예 (GiST, 반경 검색)")
print(after["반경 5km 검색 (ST_DWithin)"][3])
