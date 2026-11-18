package io.github.cheatiskey.subsidy.ingestion;

/** 공공 API 호출이 끝내 실패한 페이지의 기록 칸 (SYNC-6). httpStatus는 예외에 있을 때만, errorCode는 실패 종류. */
public record PageFailure(Integer httpStatus, String errorCode, int attemptCount) {
}
