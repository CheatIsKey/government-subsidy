package io.github.cheatiskey.subsidy.ingestion;

public record SyncFailureView(
        SyncStage stage,
        String serviceId,
        Integer page,
        Integer httpStatus,
        String errorCode,
        String message) {
}
