package io.github.cheatiskey.subsidy.ingestion;

import static io.github.cheatiskey.subsidy.ingestion.Gov24Fixtures.items;
import static io.github.cheatiskey.subsidy.ingestion.Gov24Fixtures.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * SYNC-6 1차: 실행 도중의 상태 (통합). 공공 API 대신 테스트가 호출마다 결과를 정하는 가짜 목록 공급자를 쓴다
 * (재시도 도중에 멈춰 세우고, 공공 API가 아닌 예외를 던지기 위해). 재시도 간격 0s·지터 0.
 * 시계는 테스트가 움직일 수 있는 시계로 바꿔 끼운다 (heartbeat 갱신을 보려고).
 */
@TestPropertySource(properties = {
        "subsidy.gov24.retry.max-attempts=4",
        "subsidy.gov24.retry.intervals=0s,0s,0s",
        "subsidy.gov24.retry.jitter-ratio=0"
})
class SyncRunFailureIntegrationTest extends SyncIntegrationTestSupport {

    static final MovableClock CLOCK = new MovableClock();

    @TestBean(name = "fixedClock", methodName = "movableClock")
    Clock clock;

    static Clock movableClock() {
        return CLOCK;
    }

    @Autowired
    SyncService syncService;

    @Autowired
    SyncRunQueryService syncRunQueryService;

    @Autowired
    DelegatingServiceListSource source;

    private final CountDownLatch release = new CountDownLatch(1);
    private CompletableFuture<SyncRunView> background;

    @BeforeEach
    void resetClock() {
        CLOCK.set(NOW);
    }

    @AfterEach
    void finishBackgroundRun() {
        release.countDown();
        if (background != null) {
            background.handle((run, error) -> null).orTimeout(30, TimeUnit.SECONDS).join();
        }
    }

    private Instant heartbeatOfLatestRun() {
        return jdbc.queryForObject("select heartbeat_at from sync_run order by id desc limit 1", OffsetDateTime.class)
                .toInstant();
    }

    @Test
    @DisplayName("SYNC-6-E1 2페이지를 재시도하는 동안 조회 → RUNNING, 지금까지의 건수(100), 실패한 호출 뒤 heartbeat가 갱신됨")
    void e1_queryWhileRetrying() {
        Instant afterFirstFailure = NOW.plusSeconds(10);
        CountDownLatch blocked = new CountDownLatch(1);
        AtomicInteger secondPageCalls = new AtomicInteger();
        source.use((pageNo, perPage) -> {
            if (pageNo == 2 && secondPageCalls.incrementAndGet() == 1) {
                CLOCK.set(afterFirstFailure);
                throw Gov24Errors.httpError(503, "/api/gov24/v3/serviceList", 2, "", "test-service-key");
            }
            if (pageNo == 2) {
                blocked.countDown();
                awaitLatch(release);
            }
            return page(pageNo, 100, 250, items(pageNo * 100 - 99, Math.min(pageNo * 100, 250)));
        });

        background = CompletableFuture.supplyAsync(syncService::runFull);

        assertThat(awaitLatch(blocked)).as("실행이 2페이지 두 번째 호출에 도달").isTrue();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            SyncRunView running = syncRunQueryService.list(1).get(0);
            assertThat(running.status()).isEqualTo(SyncRunStatus.RUNNING);
            assertThat(running.fetched()).isEqualTo(100);
            assertThat(heartbeatOfLatestRun()).isEqualTo(afterFirstFailure);
        });

        release.countDown();
        assertThat(background.orTimeout(30, TimeUnit.SECONDS).join().status()).isEqualTo(SyncRunStatus.SUCCEEDED);
    }

    static Stream<RuntimeException> nonApiErrors() {
        return Stream.of(
                new IllegalStateException("예상 못 한 오류"),
                new DataAccessResourceFailureException("DB 연결 끊김"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("nonApiErrors")
    @DisplayName("SYNC-6-F12 공공 API가 아닌 예외 → FAILED INTERNAL_ERROR로 기록한 뒤 같은 예외를 다시 던짐, RUNNING 남지 않음")
    void f12_unexpectedErrorIsRecordedThenRethrown(RuntimeException error) {
        source.use((pageNo, perPage) -> {
            if (pageNo == 2) {
                throw error;
            }
            return page(pageNo, 100, 250, items(1, 100));
        });

        Throwable thrown = catchThrowable(syncService::runFull);

        assertThat(thrown).isSameAs(error);
        List<SyncRunView> runs = syncRunQueryService.list(20);
        assertThat(runs).extracting(SyncRunView::status).containsExactly(SyncRunStatus.FAILED);
        assertThat(runs).extracting(SyncRunView::errorCode).containsExactly("INTERNAL_ERROR");
    }

    private static boolean awaitLatch(CountDownLatch latch) {
        try {
            return latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 테스트가 정한 공급자에게 그대로 넘긴다. 정하지 않았으면 실패. */
    static class DelegatingServiceListSource implements ServiceListSource {

        private volatile ServiceListSource delegate = (page, perPage) -> {
            throw new AssertionError("공급자를 정하지 않았다: page=" + page);
        };

        void use(ServiceListSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public String fetchPage(int page, int perPage) {
            return delegate.fetchPage(page, perPage);
        }
    }

    /** 테스트가 시각을 정하는 시계. */
    static final class MovableClock extends Clock {

        private volatile Instant now = NOW;

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }

    @TestConfiguration
    static class SourceConfig {

        @Bean
        @Primary
        DelegatingServiceListSource delegatingServiceListSource() {
            return new DelegatingServiceListSource();
        }
    }
}
