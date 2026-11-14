package io.github.cheatiskey.subsidy.ingestion;

public record RejectedItem(SyncStage stage, String serviceId, String reason) {
}
