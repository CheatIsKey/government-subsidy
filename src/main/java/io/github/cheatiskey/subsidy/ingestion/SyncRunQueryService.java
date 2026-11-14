package io.github.cheatiskey.subsidy.ingestion;

import static io.github.cheatiskey.subsidy.ingestion.JdbcTimes.fromDb;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 실행 기록 조회 (SYNC-9). 커밋된 값만 읽으므로 실행 중에도 지금까지의 건수가 보인다. */
@Service
public class SyncRunQueryService {

    private static final String RUN_COLUMNS = """
            select id, type, status, error_code, started_at, finished_at, source_total_count,
                   fetched, created, updated, unchanged, removed, failed
              from sync_run""";

    private final JdbcTemplate jdbc;

    public SyncRunQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SyncRunView> find(long runId) {
        return jdbc.query(RUN_COLUMNS + " where id = ?", SyncRunQueryService::toRun, runId).stream().findFirst();
    }

    /** 최신 실행이 먼저 (runId 내림차순). */
    public List<SyncRunView> list(int size) {
        return jdbc.query(RUN_COLUMNS + " order by id desc limit ?", SyncRunQueryService::toRun, size);
    }

    public List<SyncFailureView> failures(long runId) {
        return jdbc.query("""
                select stage, service_id, page, http_status, error_code, message
                  from sync_failure
                 where run_id = ?
                 order by id""",
                (rs, n) -> new SyncFailureView(
                        SyncStage.valueOf(rs.getString("stage")),
                        rs.getString("service_id"),
                        rs.getObject("page", Integer.class),
                        rs.getObject("http_status", Integer.class),
                        rs.getString("error_code"),
                        rs.getString("message")),
                runId);
    }

    private static SyncRunView toRun(ResultSet rs, int rowNum) throws SQLException {
        return new SyncRunView(
                rs.getLong("id"),
                SyncType.valueOf(rs.getString("type")),
                SyncRunStatus.valueOf(rs.getString("status")),
                rs.getString("error_code"),
                fromDb(rs, "started_at"),
                fromDb(rs, "finished_at"),
                rs.getObject("source_total_count", Integer.class),
                rs.getInt("fetched"),
                rs.getInt("created"),
                rs.getInt("updated"),
                rs.getInt("unchanged"),
                rs.getInt("removed"),
                rs.getInt("failed"));
    }
}
