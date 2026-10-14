"""WireMock(기상청 대역)에 시나리오별 응답을 설정한다. 표준 라이브러리만 사용.

사용법: python perf/setup_wiremock.py <scenario> [wiremock_url]
시나리오: ok | delay5s | delay1s | http500 | resultcode | malformed
"""
import json
import sys
import urllib.request

ULTRA = {"response": {"header": {"resultCode": "00", "resultMsg": "NORMAL_SERVICE"}, "body": {"items": {"item": [
    {"category": "T1H", "obsrValue": "18.5"}, {"category": "RN1", "obsrValue": "0"}, {"category": "SKY", "obsrValue": "1"}]}}}}
VILAGE = {"response": {"header": {"resultCode": "00", "resultMsg": "NORMAL_SERVICE"}, "body": {"items": {"item": [
    {"fcstDate": "20261014", "fcstTime": "1200", "category": "TMP", "fcstValue": "20"},
    {"fcstDate": "20261014", "fcstTime": "1200", "category": "POP", "fcstValue": "30"},
    {"fcstDate": "20261014", "fcstTime": "1200", "category": "SKY", "fcstValue": "3"}]}}}}
RESULT_CODE_ERR = {"response": {"header": {"resultCode": "22", "resultMsg": "LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR"}}}


JSON = {"Content-Type": "application/json"}


def response_for(scenario, payload):
    if scenario == "ok":
        return {"status": 200, "jsonBody": payload, "headers": JSON}
    if scenario == "delay5s":
        return {"status": 200, "jsonBody": payload, "headers": JSON, "fixedDelayMilliseconds": 5000}
    if scenario == "delay1s":
        return {"status": 200, "jsonBody": payload, "headers": JSON, "fixedDelayMilliseconds": 1000}
    if scenario == "http500":
        return {"status": 500, "body": "upstream error"}
    if scenario == "resultcode":
        return {"status": 200, "jsonBody": RESULT_CODE_ERR, "headers": JSON}
    if scenario == "malformed":
        return {"status": 200, "body": "this is not json", "headers": {"Content-Type": "application/json"}}
    raise SystemExit("unknown scenario: " + scenario)


def post(base, path, body=None, method="POST"):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method, headers={"Content-Type": "application/json"})
    urllib.request.urlopen(req).read()


def main():
    scenario = sys.argv[1]
    base = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:8089"
    post(base, "/__admin/mappings/reset")
    post(base, "/__admin/requests", method="DELETE")
    for path, payload in (("/getUltraSrtNcst", ULTRA), ("/getVilageFcst", VILAGE)):
        post(base, "/__admin/mappings", {"request": {"method": "GET", "urlPath": path},
                                          "response": response_for(scenario, payload)})
    print("wiremock scenario set:", scenario)


if __name__ == "__main__":
    main()
