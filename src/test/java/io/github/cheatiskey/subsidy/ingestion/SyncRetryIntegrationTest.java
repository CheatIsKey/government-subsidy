package io.github.cheatiskey.subsidy.ingestion;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static io.github.cheatiskey.subsidy.ingestion.ServiceKeyForms.KEY;
import static io.github.cheatiskey.subsidy.ingestion.ServiceKeyForms.assertNoKey;
import static io.github.cheatiskey.subsidy.ingestion.ServiceKeyForms.assertNoKeyInLongText;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * SYNC-6 1차: 공공 API 실패를 실행 안에서 재시도하고, 끝내 실패하면 실행 기록에 남긴다 (통합, WireMock).
 * 재시도 최대 4번·간격 0s(느려지지 않게). HTTP 오류 시험이라 타임아웃은 넉넉하게 5초.
 * 타임아웃 시험은 SyncRetryTimeoutIntegrationTest, 횟수 설정 시험은 SyncRetryConfiguredIntegrationTest.
 */
@ActiveProfiles("local")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
        "subsidy.gov24.connect-timeout=5s",
        "subsidy.gov24.read-timeout=5s",
        "subsidy.gov24.retry.max-attempts=4",
        "subsidy.gov24.retry.intervals=0s,0s,0s"
})
class SyncRetryIntegrationTest extends Gov24RetrySyncSupport {

    @Autowired
    MockMvcTester mvc;

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

    /** 1·2페이지 정상, 3페이지가 이 status(401/403) → 재시도 없이 실행 중단, 실패 행 1줄. */
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
            assertSingleFailureRow(run.runId(), 3, status, "HTTP_ERROR", 1);
            assertThat(logs.at(Level.ERROR)).hasSize(1).allSatisfy(ServiceKeyForms::assertNoKey);
        }
        assertNothingRemoved(3);
        assertNoRunLeftRunning();
    }

    @Test
    @DisplayName("SYNC-6-F1 3페이지 401 → 재시도 없음, FAILED AUTH_FAILED, 예외 없음, 실패 행 (LIST,3,401,HTTP_ERROR,1), ERROR 로그 1건(키 없음), 1·2페이지도 저장 안 함")
    void f1_unauthorizedStopsRunWithoutSaving() {
        assertAuthFailureStopsRun(401);
    }

    @Test
    @DisplayName("SYNC-6-F11-403 3페이지 403 → F1과 같음(재시도 없음, FAILED AUTH_FAILED, 실패 행 (LIST,3,403,HTTP_ERROR,1), 저장 안 함)")
    void f11_403_forbiddenStopsRunWithoutSaving() {
        assertAuthFailureStopsRun(403);
    }

    @Test
    @DisplayName("SYNC-6-F3 2페이지 500 4번 연속 → 호출 4번, LIST 실패(page=2, 500, HTTP_ERROR, 4번), 3페이지 계속, PARTIAL, 삭제 판정 안 함")
    void f3_serverErrorFourTimesIsRecorded() {
        assertSecondPageGivesUp(status(500), 500, "HTTP_ERROR", 4);
    }

    @ParameterizedTest(name = "[{index}] HTTP {0}")
    @ValueSource(ints = {429, 408})
    @DisplayName("SYNC-6-F2 2페이지 429·408 4번 연속 → F3과 같음(호출 4번, HTTP_ERROR 4번 기록, PARTIAL)")
    void f2_tooManyRequestsAndRequestTimeoutAreRetried(int status) {
        assertSecondPageGivesUp(status(status), status, "HTTP_ERROR", 4);
    }

    @Test
    @DisplayName("SYNC-6-F11 2페이지 404 → 재시도 없음(호출 1번), 실패 행 (LIST,2,404,HTTP_ERROR,1), 3페이지 계속, PARTIAL")
    void f11_notFoundIsRecordedWithoutRetry() {
        assertSecondPageGivesUp(status(404), 404, "HTTP_ERROR", 1);
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
    @DisplayName("SYNC-6-A1 2페이지 실패 뒤 실패 행·WARN 이상 로그가 있고, sync_failure(message·error_code), error_summary, 운영 API, 로그, 콘솔에 키 없음")
    void a1_failureRecordsAndLogsHaveNoKey(int status, CapturedOutput output) throws Exception {
        okPage(1, 250, 1, 100);
        onPage(2, status == 401 ? aResponse().withStatus(401).withBody(KEY_ERROR_BODY) : status(500));
        okPage(3, 250, 201, 250);

        long runId;
        try (Logs logs = Logs.watch()) {
            runId = runFullRecorded().runId();
            assertThat(logs.warnOrAbove()).as("WARN 이상 로그").isNotEmpty();
            assertThat(logs.all()).allSatisfy(ServiceKeyForms::assertNoKey);
        }

        List<Map<String, Object>> rows = failureRows(runId);
        assertThat(rows).as("실패 행").isNotEmpty();
        assertThat(rows).allSatisfy(row -> {
            assertThat((String) row.get("message")).startsWith("HTTP_ERROR status=" + status);
            assertNoKey((String) row.get("message"));
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
}
