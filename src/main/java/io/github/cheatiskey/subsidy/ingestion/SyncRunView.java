package io.github.cheatiskey.subsidy.ingestion;

import java.time.Instant;

// 껍데기(2단계 테스트 컴파일용). 로직 없음. 3단계에서 구현한다.
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
