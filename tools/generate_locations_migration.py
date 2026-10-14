"""기상청 동네예보 격자 위경도 엑셀을 Flyway 마이그레이션(SQL)으로 바꾼다.

출처: 기상청 단기예보 조회서비스 오픈API 활용가이드의 '격자 위경도' 엑셀 (공공데이터포털, 공공누리 제1유형 - 출처표시)
  https://www.data.go.kr/data/15084084/openapi.do  (참고문서 zip 안의 xlsx)

사용법 (openpyxl 필요: pip install openpyxl)
  python tools/generate_locations_migration.py <엑셀 경로> backend/src/main/resources/db/migration/V4__load_kma_locations.sql

엑셀 컬럼: 구분, 행정구역코드, 1단계, 2단계, 3단계, 격자 X, 격자 Y, 경도(시/분/초), 위도(시/분/초), 경도(초/100), 위도(초/100), 위치업데이트
  - 경도(초/100), 위도(초/100)은 십진 도(degree) 값이다.
  - 경도·위도가 0인 행(이어도 해양과학기지 2행)은 위치로 쓸 수 없어 제외한다.
"""
import sys

import openpyxl

BATCH = 500


def sql_str(value):
    if value in (None, ''):
        return 'NULL'
    return "'" + str(value).replace("'", "''") + "'"


def main(xlsx_path, out_path):
    sheet = openpyxl.load_workbook(xlsx_path, read_only=True, data_only=True).worksheets[0]
    rows = [r for r in list(sheet.iter_rows(values_only=True))[1:] if any(c not in (None, '') for c in r)]

    values, skipped = [], []
    for r in rows:
        code, sido, sigungu, dong = str(r[1]), r[2], r[3] or None, r[4] or None
        nx, ny, lon, lat = int(r[5]), int(r[6]), float(r[13]), float(r[14])
        if lon == 0 or lat == 0:
            skipped.append((code, sido, sigungu, dong))
            continue
        full_name = ' '.join(x for x in (sido, sigungu, dong) if x)
        values.append("(%s, %s, %s, %s, %s, %d, %d, ST_SetSRID(ST_MakePoint(%r, %r), 4326)::geography)" % (
            sql_str(code), sql_str(sido), sql_str(sigungu), sql_str(dong), sql_str(full_name), nx, ny, lon, lat))

    lines = [
        "-- 기상청 동네예보 행정구역별 격자 위경도 (%d행)" % len(values),
        "-- 출처: 기상청 단기예보 조회서비스 오픈API 활용가이드 '격자 위경도' (공공데이터포털, 공공누리 제1유형 - 출처표시)",
        "--   https://www.data.go.kr/data/15084084/openapi.do",
        "-- 생성: tools/generate_locations_migration.py (원본 엑셀의 시트 '%s')" % sheet.title,
        "-- 제외: 경도·위도가 0인 %d행 (%s)" % (len(skipped), ', '.join(sorted({s[1] for s in skipped})) or '없음'),
        "",
    ]
    for i in range(0, len(values), BATCH):
        lines.append("INSERT INTO locations (region_code, sido, sigungu, eupmyeondong, full_name, nx, ny, geom) VALUES")
        lines.append(",\n".join(values[i:i + BATCH]) + ";")
        lines.append("")
    lines.append("ANALYZE locations;")

    with open(out_path, 'w', encoding='utf-8', newline='\n') as f:
        f.write("\n".join(lines) + "\n")
    print("rows: %d, skipped: %d -> %s" % (len(values), len(skipped), out_path))


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
