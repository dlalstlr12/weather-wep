package com.example.weather.service;

/** 기상청 조회 결과. stale이면 갱신에 실패해 캐시에 남아 있던 지난 값을 대신 준 것이다. */
public record KmaResult<T>(T value, boolean stale, long ageSeconds) {}
