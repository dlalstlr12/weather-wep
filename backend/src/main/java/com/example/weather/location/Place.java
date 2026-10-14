package com.example.weather.location;

/** 위치 테이블의 한 행정구역. distanceMeters는 기준점이 있는 조회(nearby, nearest)에서만 채워진다. */
public record Place(
        long id,
        String fullName,
        String sido,
        String sigungu,
        String eupmyeondong,
        int nx,
        int ny,
        double lat,
        double lon,
        Double distanceMeters) {}
