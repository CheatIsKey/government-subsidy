package io.github.cheatiskey.subsidy.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 동기화 통합 테스트 공통 준비: 진짜 PostgreSQL(Testcontainers) + Flyway 스키마 + 고정 시계.
 * 테스트마다 스키마를 지우고 마이그레이션을 다시 돌려 빈 DB에서 시작한다.
 * 컨테이너는 모든 테스트 클래스가 하나를 같이 쓴다.
 */
@SpringBootTest(properties = {
        "spring.flyway.clean-disabled=false",
        "subsidy.gov24.base-url=http://localhost:1",
        "subsidy.gov24.service-key=test-service-key",
        "subsidy.gov24.per-page=100"
})
@Import(SyncIntegrationTestSupport.FixedClockConfig.class)
abstract class SyncIntegrationTestSupport {

    /** 테스트 안의 "지금". 실행 기록의 시각은 모두 이 값이어야 한다. */
    static final Instant NOW = Instant.parse("2026-11-14T03:00:00Z");

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void resetSchema() {
        flyway.clean();
        flyway.migrate();
    }

    int benefitCount() {
        return jdbc.queryForObject("select count(*) from benefit", Integer.class);
    }

    String benefitName(String serviceId) {
        return jdbc.queryForObject("select name from benefit where service_id = ?", String.class, serviceId);
    }

    /** 혜택마다 (service_id, status, missing_count). 삭제 판정을 했는지 보는 데 쓴다. */
    List<Map<String, Object>> benefitStates() {
        return jdbc.queryForList("select service_id, status, missing_count from benefit order by service_id");
    }

    /** 혜택이 expectedCount건이고, 모두 ACTIVE이며 missing_count가 0이다 (삭제 판정을 하지 않았다). */
    void assertNothingRemoved(int expectedCount) {
        assertThat(benefitStates()).hasSize(expectedCount)
                .allSatisfy(row -> {
                    assertThat(row.get("status")).isEqualTo("ACTIVE");
                    assertThat(((Number) row.get("missing_count")).intValue())
                            .isEqualTo(0);
                });
    }

    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
