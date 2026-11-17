package io.github.cheatiskey.subsidy.ingestion;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.github.cheatiskey.subsidy.ingestion.ServiceKeyForms.KEY;
import static io.github.cheatiskey.subsidy.ingestion.ServiceKeyForms.assertNoKey;
import static io.github.cheatiskey.subsidy.ingestion.ServiceKeyForms.assertNoKeyInLongText;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.tuple;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * SYNC-6 1차: 공공 API 실패를 실행 안에서 재시도하고, 끝내 실패하면 실행 기록에 남긴다 (통합, WireMock).
 * 재시도 간격 0s·지터 0(느려지지 않게), 응답 타임아웃 1초(TIMEOUT 시험의 가짜 지연은 3초, 제한의 3배).
 * 목록은 totalCount=250, perPage=100 (3페이지).
 */
@ActiveProfiles("local")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
        "subsidy.gov24.connect-timeout=5s",
        "subsidy.gov24.read-timeout=1s",
        "subsidy.gov24.retry.max-attempts=4",
        "subsidy.gov24.retry.intervals=0s,0s,0s",
        "subsidy.gov24.retry.jitter-ratio=0"
})
class SyncRetryIntegrationTest extends Gov24StubbedSyncSupport {

    @Autowired
    MockMvcTester mvc;

    /** 실행 전에 넣어 둔 혜택: 2페이지에 해당하는 id(101)~id(103). 실패 실행이 이것을 지우거나 바꾸면 안 된다. */
    private void seedThreeBenefitsOfPageTwo() {
        okPage(1, 3, 101, 103);
        assertThat(syncService.runFull().status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        GOV24.resetAll();
    }

    private static ResponseDefinitionBuilder status(int status) {
        return aResponse().withStatus(status).withBody("<html>error " + KEY + "</html>");
    }

    /** 이 page 요청에 응답을 차례로 준다. 마지막 응답은 그 뒤로 계속 준다. */
    private static void onPageInOrder(int page, ResponseDefinitionBuilder... responses) {
        for (int i = 0; i < responses.length; i++) {
            var stub = get(urlPathEqualTo(LIST_PATH)).withQueryParam("page", equalTo(String.valueOf(page)))
                    .inScenario("page-" + page)
                    .whenScenarioStateIs(i == 0 ? Scenario.STARTED : "call-" + i)
                    .willReturn(responses[i]);
            GOV24.stubFor(i < responses.length - 1 ? stub.willSetStateTo("call-" + (i + 1)) : stub);
        }
    }

    private static int calls(int page) {
        return GOV24.findAll(getRequestedFor(urlPathEqualTo(LIST_PATH))
                .withQueryParam("page", equalTo(String.valueOf(page)))).size();
    }

    /** 공공 API 실패에서 runFull은 예외를 던지지 않고 끝난 실행 기록을 돌려준다. */
    private SyncRunView runFullRecorded() {
        AtomicReference<SyncRunView> run = new AtomicReference<>();
        assertThatCode(() -> run.set(syncService.runFull()))
                .as("공공 API 실패에서 runFull은 예외를 던지지 않는다").doesNotThrowAnyException();
        return run.get();
    }

    /** sync_failure 행마다 (stage, page, http_status, error_code, attempt_count). */
    private List<Map<String, Object>> failureRows(long runId) {
        return jdbc.queryForList("""
                select stage, page, http_status, error_code, attempt_count, message
                  from sync_failure where run_id = ? order by id""", runId);
    }

    private void assertNoRunLeftRunning() {
        assertThat(syncRunQueryService.list(20)).extracting(SyncRunView::status)
                .doesNotContain(SyncRunStatus.RUNNING);
    }

    @Test
    @DisplayName("SYNC-6-N1 1페이지 503 1번 → 재시도에서 200, 나머지 정상 → SUCCEEDED, 실패 기록 0건, 1페이지 호출 2번")
    void n1_retryRecoversFromOne503() {
        onPageInOrder(1, status(503), aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(Gov24Fixtures.page(1, 100, 250, Gov24Fixtures.items(1, 100))));
        okPage(2, 250, 101, 200);
        okPage(3, 250, 201, 250);

        SyncRunView run = runFullRecorded();

        assertThat(run.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(failureRows(run.runId())).isEmpty();
        assertThat(calls(1)).isEqualTo(2);
        assertThat(benefitCount()).isEqualTo(250);
    }

    /** 1·2페이지 정상, 3페이지가 이 status(401/403) → 재시도 없이 실행 중단. */
    private void assertAuthFailureStopsRun(int status) {
        okPage(1, 3, 1, 3);
        assertThat(syncService.runFull().status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        GOV24.resetAll();
        okPage(1, 250, 1, 100);
        okPage(2, 250, 101, 200);
        onPage(3, aResponse().withStatus(status).withHeader("Content-Type", "application/json")
                .withBody(KEY_ERROR_BODY));

        try (Logs logs = Logs.watch()) {
            SyncRunView run = runFullRecorded();

            assertThat(calls(3)).as("재시도 없음").isEqualTo(1);
            assertThat(run.status()).isEqualTo(SyncRunStatus.FAILED);
            assertThat(run.errorCode()).isEqualTo("AUTH_FAILED");
            assertThat(logs.at(Level.ERROR)).hasSize(1).allSatisfy(ServiceKeyForms::assertNoKey);
        }
        assertNothingRemoved(3);
        assertNoRunLeftRunning();
    }

    @Test
    @DisplayName("SYNC-6-F1 3페이지 401 → 재시도 없음, FAILED AUTH_FAILED, 예외 없음, ERROR 로그 1건(키 없음), 1·2페이지도 저장 안 함")
    void f1_unauthorizedStopsRunWithoutSaving() {
        assertAuthFailureStopsRun(401);
    }

    @Test
    @DisplayName("SYNC-6-F11-403 3페이지 403 → F1과 같음(재시도 없음, FAILED AUTH_FAILED, 저장 안 함)")
    void f11_403_forbiddenStopsRunWithoutSaving() {
        assertAuthFailureStopsRun(403);
    }

    /** 2페이지가 계속 이 응답 → 4번 부르고 실패 기록, 3페이지 계속, PARTIAL, 삭제 판정 안 함. */
    private void assertSecondPageGivesUp(ResponseDefinitionBuilder response, Integer httpStatus, String errorCode) {
        seedThreeBenefitsOfPageTwo();
        okPage(1, 250, 1, 100);
        onPage(2, response);
        okPage(3, 250, 201, 250);

        SyncRunView run = runFullRecorded();

        assertThat(calls(2)).isEqualTo(4);
        assertThat(calls(3)).isEqualTo(1);
        assertThat(failureRows(run.runId()))
                .extracting(r -> r.get("stage"), r -> r.get("page"), r -> r.get("http_status"),
                        r -> r.get("error_code"), r -> r.get("attempt_count"))
                .containsExactly(tuple("LIST", 2, httpStatus, errorCode, 4));
        assertThat(run.status()).isEqualTo(SyncRunStatus.PARTIAL);
        assertNothingRemoved(153);
    }

    @Test
    @DisplayName("SYNC-6-F3 2페이지 500 4번 연속 → 호출 4번, LIST 실패(page=2, 500, HTTP_ERROR, 4번), 3페이지 계속, PARTIAL, 삭제 판정 안 함")
    void f3_serverErrorFourTimesIsRecorded() {
        assertSecondPageGivesUp(status(500), 500, "HTTP_ERROR");
    }

    @Test
    @DisplayName("SYNC-6-F2 2페이지 429 4번 연속 → F3과 같음(호출 4번, 429 HTTP_ERROR 4번 기록, PARTIAL)")
    void f2_tooManyRequestsIsRetriedLikeServerError() {
        assertSecondPageGivesUp(status(429), 429, "HTTP_ERROR");
    }

    @Test
    @DisplayName("SYNC-6-F4 2페이지 응답 타임아웃 4번 연속 → F3과 같음, http_status 없음, error_code=TIMEOUT")
    void f4_readTimeoutFourTimesIsRecorded() {
        assertSecondPageGivesUp(aResponse().withStatus(200).withBody("{\"data\":[]}").withFixedDelay(3000),
                null, "TIMEOUT");
    }

    @Test
    @DisplayName("SYNC-6-F10 1페이지가 재시도까지 모두 500 → 호출 4번, FAILED SOURCE_UNAVAILABLE, 저장 없음, RUNNING 남지 않음")
    void f10_firstPageUnavailableFailsRun() {
        seedThreeBenefitsOfPageTwo();
        onPage(1, status(500));

        SyncRunView run = runFullRecorded();

        assertThat(calls(1)).isEqualTo(4);
        assertThat(run.status()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(run.errorCode()).isEqualTo("SOURCE_UNAVAILABLE");
        assertNothingRemoved(3);
        assertNoRunLeftRunning();
    }

    @ParameterizedTest(name = "[{index}] HTTP {0}")
    @ValueSource(ints = {500, 401})
    @DisplayName("SYNC-6-A1 2페이지 실패 뒤 sync_failure(message·error_code), error_summary, 운영 API, 로그(WARN·ERROR)와 콘솔에 키 없음")
    void a1_failureRecordsAndLogsHaveNoKey(int status, CapturedOutput output) throws Exception {
        okPage(1, 250, 1, 100);
        onPage(2, status == 401 ? aResponse().withStatus(401).withBody(KEY_ERROR_BODY) : status(500));
        okPage(3, 250, 201, 250);

        long runId;
        try (Logs logs = Logs.watch()) {
            runId = runFullRecorded().runId();
            assertThat(logs.all()).allSatisfy(ServiceKeyForms::assertNoKey);
        }

        assertThat(failureRows(runId)).allSatisfy(row -> {
            assertNoKey(Objects.toString(row.get("message"), ""));
            assertNoKey(Objects.toString(row.get("error_code"), ""));
        });
        assertNoKey(Objects.toString(
                jdbc.queryForObject("select error_summary from sync_run where id = ?", String.class, runId), ""));
        for (String uri : new String[] {
                "/ops/v1/sync-runs?size=20", "/ops/v1/sync-runs/" + runId, "/ops/v1/sync-runs/" + runId + "/failures"}) {
            var result = mvc.get().uri(uri).exchange();
            assertThat(result).as(uri).hasStatusOk();
            assertNoKey(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        }
        assertNoKeyInLongText(output.getAll());
    }

    /** 이 앱(io.github.cheatiskey.subsidy) 로거가 남긴 로그를 로거에서 직접 센다 (docs/decisions.md 로그 확인 방법). */
    static final class Logs implements AutoCloseable {

        private final Logger logger = (Logger) LoggerFactory.getLogger("io.github.cheatiskey.subsidy");
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        static Logs watch() {
            Logs logs = new Logs();
            logs.appender.start();
            logs.logger.addAppender(logs.appender);
            return logs;
        }

        List<String> all() {
            return appender.list.stream().map(Logs::line).toList();
        }

        List<String> at(Level level) {
            return appender.list.stream().filter(e -> e.getLevel() == level).map(Logs::line).toList();
        }

        private static String line(ILoggingEvent e) {
            String thrown = e.getThrowableProxy() == null ? "" : " " + e.getThrowableProxy().getMessage();
            return e.getLoggerName() + " " + e.getLevel() + " " + e.getFormattedMessage() + thrown;
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
