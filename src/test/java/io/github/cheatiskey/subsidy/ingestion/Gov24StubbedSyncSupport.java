package io.github.cheatiskey.subsidy.ingestion;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static io.github.cheatiskey.subsidy.ingestion.Gov24Fixtures.items;
import static io.github.cheatiskey.subsidy.ingestion.Gov24Fixtures.page;
import static io.github.cheatiskey.subsidy.ingestion.ServiceKeyForms.KEY;
import static io.github.cheatiskey.subsidy.ingestion.ServiceKeyForms.assertNoKey;
import static io.github.cheatiskey.subsidy.ingestion.ServiceKeyForms.assertNoKeyInLongText;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.Slf4jNotifier;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * OPS-5 통합 테스트 공통: 가짜 공공 API(WireMock, HTTP/1.1만, 알림 로그 끔) + 동기화 실행.
 * 요청은 경로와 page로만 맞춘다(맞지 않는 요청 로그가 섞이지 않게). 타임아웃은 하위 클래스가 정한다.
 */
abstract class Gov24StubbedSyncSupport extends SyncIntegrationTestSupport {

    static final String LIST_PATH = "/api/gov24/v3/serviceList";
    static final String KEY_ERROR_BODY = Gov24Fixtures.load("error-invalid-key-401.json");
    static final WireMockServer GOV24 = new WireMockServer(options().dynamicPort()
            .http2PlainDisabled(true).notifier(new Slf4jNotifier(false)));

    /** 테스트 동안 끄는 가짜 서버 쪽 로거. */
    static final String[] FAKE_SERVER_LOGGERS = {"WireMock", "wiremock", "com.github.tomakehurst.wiremock"};

    static {
        GOV24.start();
    }

    @DynamicPropertySource
    static void gov24(DynamicPropertyRegistry registry) {
        registry.add("subsidy.gov24.base-url", GOV24::baseUrl);
        registry.add("subsidy.gov24.service-key", () -> KEY);
        // SYNC-6: 재시도 간격 0s (실패 시험이 21초씩 느려지지 않게)
        registry.add("subsidy.gov24.retry.intervals", () -> "0s,0s,0s");
    }

    @Autowired
    SyncService syncService;

    @Autowired
    SyncRunQueryService syncRunQueryService;

    @BeforeEach
    void resetGov24() {
        GOV24.resetAll();
    }

    static void onPage(int page, ResponseDefinitionBuilder response) {
        GOV24.stubFor(get(urlPathEqualTo(LIST_PATH)).withQueryParam("page", equalTo(String.valueOf(page)))
                .willReturn(response));
    }

    /** totalCount 기준 정상 목록 페이지 (perPage=100). */
    static void okPage(int page, int totalCount, int from, int to) {
        onPage(page, aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(page(page, 100, totalCount, items(from, to))));
    }

    /**
     * 공공 API 실패에서 runFull은 예외를 던지지 않고 실패를 기록한다 (SYNC-6 1차).
     * 끝난 실행은 FAILED 또는 PARTIAL이다.
     */
    SyncRunView runFullRecordsFailure() {
        AtomicReference<SyncRunView> run = new AtomicReference<>();
        assertThatCode(() -> run.set(syncService.runFull()))
                .as("공공 API 실패에서 runFull은 예외를 던지지 않는다").doesNotThrowAnyException();
        assertThat(run.get().status()).isIn(SyncRunStatus.FAILED, SyncRunStatus.PARTIAL);
        return run.get();
    }

    /**
     * OPS-5-E11 공통 확인: runFull이 실패를 기록하고(실패 행 1건 이상), 실패 행(message·error_code)·error_summary·콘솔 전체에 키가 없고,
     * Gov24Client·Gov24Errors·ServiceKeyMasker 로거가 남긴 로그가 TRACE까지 0건.
     */
    SyncRunView assertRunFullLeaksNoKey(CapturedOutput output) {
        try (LoggerSpy spy = LoggerSpy.watch(Gov24Client.class, Gov24Errors.class, ServiceKeyMasker.class)
                .silence(FAKE_SERVER_LOGGERS)) {
            SyncRunView run = runFullRecordsFailure();

            List<SyncFailureView> failures = syncRunQueryService.failures(run.runId());
            assertThat(failures).as("실패 행 (빈손 통과 방지)").isNotEmpty();
            failures.forEach(f -> {
                assertNoKey(Objects.toString(f.message(), ""));
                assertNoKey(Objects.toString(f.errorCode(), ""));
            });
            assertNoKey(Objects.toString(jdbc.queryForObject(
                    "select error_summary from sync_run where id = ?", String.class, run.runId()), ""));
            assertNoKeyInLongText(output.getAll());
            assertThat(spy.events()).as("Gov24Client·Gov24Errors·ServiceKeyMasker 로그").isEmpty();
            return run;
        }
    }
}
