package io.github.cheatiskey.subsidy.ingestion;

// 껍데기(2단계 테스트 컴파일용). 로직 없음. 3단계에서 구현한다.
/** field는 공공 API 필드 이름(예: 수정일시), rawValue는 받은 값 그대로. */
public record NormalizationWarning(String serviceId, String field, String rawValue) {
}
