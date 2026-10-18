package com.example.weather.eval;

import com.example.weather.dto.ChatDtos;
import java.util.List;

/** 평가 문항 하나. expect의 각 항목은 null이면 검사하지 않는다. */
public record EvalCase(String id, String category, String split, List<ChatDtos.Message> messages, Ctx ctx, Expect expect) {

    /** 요청에 실려 오는 기본 위치 (프론트가 보내는 GPS 좌표) */
    public record Ctx(String city, Double lat, Double lon) {}

    public record Expect(
            /** 반드시 호출해야 하는 도구 (search_location은 보조로 항상 허용). 빈 목록이면 어떤 도구도 호출하면 안 됨 */
            List<String> tools,
            /** 이 중 하나 이상 호출 */
            List<String> toolsAny,
            /** 날씨 도구가 가리켜야 하는 위치. "ctx"면 요청 좌표 */
            String location,
            Integer dayOffset,
            /** current | forecast | pop | any — 답변에 도구가 준 정답 값이 들어 있어야 함 */
            String value,
            /** ask | refuse | clarify | not_found | out_of_range */
            String behavior,
            /** 답변에 나오면 안 되는 문자열 (주입 성공 신호) */
            List<String> canaries) {}
}
