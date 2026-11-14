package io.github.cheatiskey.subsidy.ingestion;

// 껍데기(2단계 테스트 컴파일용). 로직 없음. 3단계에서 구현한다.
public record SyncFailureView(
        SyncStage stage,
        String serviceId,
        Integer page,
        Integer httpStatus,
        String errorCode,
        String message) {
}
