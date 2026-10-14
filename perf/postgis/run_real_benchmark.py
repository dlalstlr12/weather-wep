"""실제 행정구역 데이터(locations 3,836행)에서 인덱스 유무에 따른 쿼리 실행 계획·시간 비교.

LocationRepository가 쓰는 SQL을 그대로 사용한다.
  docker run -d --name weather-bench -e POSTGRES_PASSWORD=bench -e POSTGRES_DB=bench postgis/postgis:16-3.4
  python perf/postgis/run_real_benchmark.py
마이그레이션 V3(테이블·인덱스), V4(데이터)를 컨테이너에 적용한 뒤 측정한다.
"""
import pathlib
import re
import statistics
import subprocess

CONTAINER = "weather-bench"
RUNS = 7
MIGRATIONS = pathlib.Path(__file__).resolve().parents[2] / "backend/src/main/resources/db/migration"

SEOUL = "ST_SetSRID(ST_MakePoint(126.9780, 37.5665), 4326)::geography"   # 서울 시청 근처 (밀집 지역)
INJE = "ST_SetSRID(ST_MakePoint(128.1700, 38.0700), 4326)::geography"    # 강원 인제 근처 (희소 지역)
COLUMNS = "id, full_name, sido, sigungu, eupmyeondong, nx, ny, ST_Y(geom::geometry) AS lat, ST_X(geom::geometry) AS lon"
RANK = "(sigungu IS NOT NULL)::int + (eupmyeondong IS NOT NULL)::int, length(full_name), id"

QUERIES = {
    "이름 검색 '역삼' (ILIKE)": f"SELECT {COLUMNS} FROM locations WHERE full_name ILIKE '%역삼%' ORDER BY {RANK} LIMIT 10",
    "반경 5km, 서울 (ST_DWithin)": f"SELECT {COLUMNS} FROM locations WHERE ST_DWithin(geom, {SEOUL}, 5000) ORDER BY geom <-> {SEOUL} LIMIT 10",
    "반경 5km, 인제 (ST_DWithin)": f"SELECT {COLUMNS} FROM locations WHERE ST_DWithin(geom, {INJE}, 5000) ORDER BY geom <-> {INJE} LIMIT 10",
    "가장 가까운 1곳, 서울 (KNN)": f"SELECT {COLUMNS} FROM locations ORDER BY geom <-> {SEOUL} LIMIT 1",
    "가장 가까운 1곳, 인제 (KNN)": f"SELECT {COLUMNS} FROM locations ORDER BY geom <-> {INJE} LIMIT 1",
}


def psql(sql):
    out = subprocess.run(
        ["docker", "exec", "-i", CONTAINER, "psql", "-U", "postgres", "-d", "bench", "-v", "ON_ERROR_STOP=1", "-At"],
        input=sql.encode("utf-8"), capture_output=True, check=True)
    return out.stdout.decode("utf-8", "replace")


def explain(query):
    text = psql("EXPLAIN (ANALYZE, BUFFERS) " + query)
    ms = float(re.search(r"Execution Time: ([\d.]+) ms", text).group(1))
    node = re.findall(r"(Seq Scan|Index Scan|Bitmap Heap Scan|Bitmap Index Scan)", text)
    return ms, " / ".join(dict.fromkeys(node)), text


def measure(label):
    print(f"\n== {label}")
    out = {}
    for name, q in QUERIES.items():
        explain(q)
        times = []
        for _ in range(RUNS):
            ms, node, text = explain(q)
            times.append(ms)
        out[name] = (statistics.median(times), node, text)
        print(f"  {name}: median {statistics.median(times):.3f} ms, 계획 {node}")
    return out


psql("DROP TABLE IF EXISTS locations; DROP TABLE IF EXISTS users;")
for f in ("V3__postgis_locations.sql", "V4__load_kma_locations.sql"):
    psql((MIGRATIONS / f).read_text(encoding="utf-8"))
print("rows:", psql("SELECT count(*) FROM locations").strip())

with_index = measure("인덱스 있음 (GiST + trigram GIN)")
psql("DROP INDEX idx_locations_geom; DROP INDEX idx_locations_full_name_trgm; ANALYZE locations;")
without = measure("인덱스 없음")
psql("CREATE INDEX idx_locations_geom ON locations USING GIST (geom); "
     "CREATE INDEX idx_locations_full_name_trgm ON locations USING GIN (full_name gin_trgm_ops); ANALYZE locations;")

print("\n== 요약 (median ms): 인덱스 없음 -> 있음")
for name in QUERIES:
    b, a = without[name][0], with_index[name][0]
    print(f"  {name}: {b:.3f} -> {a:.3f} ms ({b / a:.1f}배), {without[name][1]} -> {with_index[name][1]}")
