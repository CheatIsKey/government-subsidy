package io.github.cheatiskey.subsidy.ingestion;

// 껍데기(이슈 #6 2단계 테스트 컴파일용). 3단계에서 구현한다.
/** 공공 API 호출이 끝내 실패한 페이지의 기록 칸 (SYNC-6). httpStatus는 예외에 있을 때만. */
public record PageFailure(Integer httpStatus, String errorCode, int attemptCount) {
}
