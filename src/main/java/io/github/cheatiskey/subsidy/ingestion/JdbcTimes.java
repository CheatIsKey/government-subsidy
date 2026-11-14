package io.github.cheatiskey.subsidy.ingestion;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Instant ↔ timestamptz 변환. PostgreSQL JDBC는 Instant를 직접 받지 않아 OffsetDateTime(UTC)을 거친다. */
final class JdbcTimes {

    private JdbcTimes() {
    }

    static OffsetDateTime toDb(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    static Instant fromDb(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
