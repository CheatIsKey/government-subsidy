package io.github.cheatiskey.subsidy.ingestion;

import static io.github.cheatiskey.subsidy.ingestion.Gov24Fixtures.items;
import static io.github.cheatiskey.subsidy.ingestion.Gov24Fixtures.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.awaitility.Awaitility.await;

import java.net.http.HttpTimeoutException;
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
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * SYNC-6 1차: 실행 도중의 상태 (통합). 공공 API 대신 테스트가 호출마다 결과를 정하는 가짜 목록 공급자를 쓴다
 * (재시도 도중에 멈춰 세우고, 공공 API가 아닌 예외를 던지기 위해). 재시도 간격 0s. 저장 단계 오류는 BenefitWriter 스파이로 낸다.
 * 시계는 테스트가 움직일 수 있는 시계로 바꿔 끼운다 (heartbeat 갱신을 보려고).
 */
@TestPropertySource(properties = {
        "subsidy.gov24.retry.max-attempts=4",
        "subsidy.gov24.retry.intervals=0s,0s,0s"
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

    @MockitoSpyBean
    BenefitWriter benefitWriter;

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

    /** 실행 기록이 정확히 하나이고, FAILED INTERNAL_ERROR (RUNNING으로 남지 않음). */
    private void assertSingleRunFailedInternally() {
        List<SyncRunView> runs = syncRunQueryService.list(20);
        assertThat(runs).extracting(SyncRunView::status).containsExactly(SyncRunStatus.FAILED);
        assertThat(runs).extracting(SyncRunView::errorCode).containsExactly("INTERNAL_ERROR");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("nonApiErrors")
    @DisplayName("SYNC-6-F12 목록 공급자의 공공 API가 아닌 예외 → 재시도 없이(호출 1번) FAILED INTERNAL_ERROR로 기록한 뒤 같은 예외를 다시 던짐")
    void f12_unexpectedErrorIsRecordedThenRethrown(RuntimeException error) {
        AtomicInteger secondPageCalls = new AtomicInteger();
        source.use((pageNo, perPage) -> {
            if (pageNo == 2) {
                secondPageCalls.incrementAndGet();
                throw error;
            }
            return page(pageNo, 100, 250, items(1, 100));
        });

        Throwable thrown = catchThrowable(syncService::runFull);

        assertThat(thrown).isSameAs(error);
        assertThat(secondPageCalls).as("재시도 없음").hasValue(1);
        assertSingleRunFailedInternally();
    }

    @Test
    @DisplayName("SYNC-6-F12 저장 단계(BenefitWriter.write)의 DB 오류 → FAILED INTERNAL_ERROR로 기록한 뒤 같은 예외를 다시 던짐, RUNNING 남지 않음")
    void f12_writerDbErrorIsRecordedThenRethrown() {
        DataAccessResourceFailureException error = new DataAccessResourceFailureException("DB 연결 끊김");
        doThrow(error).when(benefitWriter).write(any(), any());
        source.use((pageNo, perPage) -> page(1, 100, 20, items(1, 20)));

        Throwable thrown = catchThrowable(syncService::runFull);

        assertThat(thrown).isSameAs(error);
        assertSingleRunFailedInternally();
    }

    /**
     * runFull을 돌려 예외를 다시 던지게 하고, 앱 전체 ERROR 로그가 정확히 1건이며
     * 예외 클래스 이름·실행 번호(run=n)만 있고 예외 메시지·키는 없는지, error_summary가 예외 클래스 이름인지 본다.
     */
    private void assertInternalErrorLoggedOnce(RuntimeException error) {
        List<String> errors;
        try (Gov24RetrySyncSupport.Logs logs = Gov24RetrySyncSupport.Logs.watch()) {
            assertThat(catchThrowable(syncService::runFull)).isSameAs(error);
            errors = logs.at(ch.qos.logback.classic.Level.ERROR);
        }
        long runId = syncRunQueryService.list(1).get(0).runId();

        assertThat(errors).as("앱 전체 ERROR 로그").hasSize(1);
        assertThat(errors.get(0))
                .contains(error.getClass().getSimpleName(), "run=" + runId)
                .doesNotContain(error.getMessage(), "test-service-key");
        assertThat(jdbc.queryForObject("select error_summary from sync_run where id = ?", String.class, runId))
                .isEqualTo(error.getClass().getName());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("nonApiErrors")
    @DisplayName("SYNC-6-F12 목록 공급자의 공공 API가 아닌 예외 → ERROR 로그 1건(예외 클래스 이름·실행 번호만, 예외 메시지·키 없음), error_summary는 예외 클래스 이름")
    void f12_unexpectedErrorLogsOnceWithoutMessage(RuntimeException error) {
        source.use((pageNo, perPage) -> {
            if (pageNo == 2) {
                throw error;
            }
            return page(pageNo, 100, 250, items(1, 100));
        });

        assertInternalErrorLoggedOnce(error);
    }

    @Test
    @DisplayName("SYNC-6-F12 저장 단계(BenefitWriter.write)의 DB 오류 → ERROR 로그 1건(예외 클래스 이름·실행 번호만, 예외 메시지·키 없음), error_summary는 예외 클래스 이름")
    void f12_writerDbErrorLogsOnceWithoutMessage() {
        DataAccessResourceFailureException error = new DataAccessResourceFailureException("DB 연결 끊김");
        doThrow(error).when(benefitWriter).write(any(), any());
        source.use((pageNo, perPage) -> page(1, 100, 20, items(1, 20)));

        assertInternalErrorLoggedOnce(error);
    }

    @Test
    @DisplayName("SYNC-6-F10 1페이지가 응답 타임아웃(TIMEOUT)으로 4번 모두 실패 → 호출 4번, FAILED SOURCE_UNAVAILABLE, 실패 행 (LIST,1,null,TIMEOUT,4), 저장 없음, 예외 없음")
    void f10_firstPageTimeoutFailsRun() {
        AtomicInteger firstPageCalls = new AtomicInteger();
        source.use((pageNo, perPage) -> {
            firstPageCalls.incrementAndGet();
            throw Gov24Errors.ioError(new HttpTimeoutException("request timed out"),
                    "/api/gov24/v3/serviceList", pageNo, "test-service-key");
        });

        AtomicReference<SyncRunView> run = new AtomicReference<>();
        assertThatCode(() -> run.set(syncService.runFull()))
                .as("공공 API 실패에서 runFull은 예외를 던지지 않는다").doesNotThrowAnyException();

        assertThat(firstPageCalls).hasValue(4);
        assertThat(run.get().status()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(run.get().errorCode()).isEqualTo("SOURCE_UNAVAILABLE");
        assertThat(jdbc.queryForList("""
                select stage, page, http_status, error_code, attempt_count from sync_failure where run_id = ?""",
                        run.get().runId()))
                .extracting(r -> r.get("stage"), r -> r.get("page"), r -> r.get("http_status"),
                        r -> r.get("error_code"), r -> r.get("attempt_count"))
                .containsExactly(tuple("LIST", 1, null, "TIMEOUT", 4));
        assertThat(benefitCount()).isZero();
        assertThat(syncRunQueryService.list(20)).extracting(SyncRunView::status).containsExactly(SyncRunStatus.FAILED);
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
