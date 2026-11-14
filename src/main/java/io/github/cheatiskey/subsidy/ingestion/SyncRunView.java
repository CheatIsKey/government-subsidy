package io.github.cheatiskey.subsidy.ingestion;

import java.time.Instant;

public record SyncRunView(
        long runId,
        SyncType type,
        SyncRunStatus status,
        String errorCode,
        Instant startedAt,
        Instant finishedAt,
        Integer sourceTotalCount,
        int fetched,
        int created,
        int updated,
        int unchanged,
        int removed,
        int failed) {
}
