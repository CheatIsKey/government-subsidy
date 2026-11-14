package io.github.cheatiskey.subsidy.ingestion;

import static io.github.cheatiskey.subsidy.ingestion.JdbcTimes.toDb;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** sync_run, sync_failure 쓰기 (SYNC-9). */
@Repository
class SyncRunRecorder {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    SyncRunRecorder(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** RUNNING 실행을 만든다. 호출한 쪽에 트랜잭션이 없으면 바로 커밋되어 다른 커넥션에서 보인다. */
    long start(SyncType type, Instant now) {
        return jdbc.queryForObject("""
                insert into sync_run (type, status, started_at, heartbeat_at)
                values (?, ?, ?, ?)
                returning id""",
                Long.class, type.name(), SyncRunStatus.RUNNING.name(), toDb(now), toDb(now));
    }

    /**
     * 페이지 하나의 진행을 자기 트랜잭션으로 따로 커밋한다 (SYNC-9-E1).
     * 실패한 페이지는 LIST 실패로 남기고 failed를 늘린다. 성공한 페이지는 fetched를 늘린다.
     */
    void recordPage(long runId, CollectedPage page, Instant now) {
        transaction.executeWithoutResult(status -> {
            if (page.failed()) {
                jdbc.update("""
                        insert into sync_failure (run_id, stage, page, message)
                        values (?, ?, ?, ?)""",
                        runId, SyncStage.LIST.name(), page.page(), "목록 페이지 응답에 data가 없거나 해석할 수 없음");
                jdbc.update("update sync_run set failed = failed + 1, heartbeat_at = ? where id = ?",
                        toDb(now), runId);
            } else {
                jdbc.update("update sync_run set fetched = fetched + ?, heartbeat_at = ? where id = ?",
                        page.items().size(), toDb(now), runId);
            }
        });
    }

    /** 검사에서 뺀 건을 VALIDATE 실패로 남긴다. 호출한 쪽의 트랜잭션에 참여한다. */
    void recordRejections(long runId, List<RejectedItem> rejected) {
        jdbc.batchUpdate("""
                insert into sync_failure (run_id, stage, service_id, message)
                values (?, ?, ?, ?)""",
                rejected.stream()
                        .map(r -> new Object[] {runId, r.stage().name(), r.serviceId(), r.reason()})
                        .toList());
    }

    /** 실행을 끝낸다. failed에는 페이지 실패에 더해 검사 실패 건수를 더한다. */
    void finish(long runId, SyncOutcome outcome, Instant now) {
        jdbc.update("""
                update sync_run
                   set status = ?, error_code = ?, error_summary = ?, finished_at = ?, heartbeat_at = ?,
                       source_total_count = ?, created = ?, updated = ?, unchanged = ?,
                       failed = failed + ?
                 where id = ?""",
                outcome.status().name(), outcome.errorCode(), outcome.errorSummary(), toDb(now), toDb(now),
                outcome.sourceTotalCount(), outcome.created(), outcome.updated(), outcome.unchanged(),
                outcome.rejected(), runId);
    }
}
