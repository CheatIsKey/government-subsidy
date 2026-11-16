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
import static io.github.cheatiskey.subsidy.ingestion.ServiceKeyForms.stackTrace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.Slf4jNotifier;
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

    /** runFull이 감싼 예외를 던지는지 확인하고 꺼낸다. */
    Gov24ApiException runFullFails() {
        Throwable thrown = catchThrowable(syncService::runFull);
        assertThat(thrown).isInstanceOf(Gov24ApiException.class);
        return (Gov24ApiException) thrown;
    }

    /**
     * OPS-5-E11 공통 확인: runFull이 감싼 예외를 던지고, 스택 트레이스와 콘솔 전체에 키가 없고,
     * Gov24Client·Gov24Errors·ServiceKeyMasker 로거가 남긴 로그가 TRACE까지 0건.
     */
    void assertRunFullLeaksNoKey(CapturedOutput output) {
        try (LoggerSpy spy = LoggerSpy.watch(Gov24Client.class, Gov24Errors.class, ServiceKeyMasker.class)
                .silence(FAKE_SERVER_LOGGERS)) {
            Gov24ApiException e = runFullFails();

            assertNoKey(stackTrace(e));
            assertNoKeyInLongText(output.getAll());
            assertThat(spy.events()).as("Gov24Client·Gov24Errors·ServiceKeyMasker 로그").isEmpty();
        }
    }
}
