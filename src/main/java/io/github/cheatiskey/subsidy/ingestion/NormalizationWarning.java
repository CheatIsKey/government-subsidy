package io.github.cheatiskey.subsidy.ingestion;

/** field는 공공 API 필드 이름(예: 수정일시), rawValue는 받은 값 그대로. */
public record NormalizationWarning(String serviceId, String field, String rawValue) {
}
