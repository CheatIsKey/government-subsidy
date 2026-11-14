package io.github.cheatiskey.subsidy.ingestion;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static io.github.cheatiskey.subsidy.ingestion.Gov24Fixtures.id;
import static io.github.cheatiskey.subsidy.ingestion.Gov24Fixtures.items;
import static io.github.cheatiskey.subsidy.ingestion.Gov24Fixtures.page;
import static io.github.cheatiskey.subsidy.ingestion.Gov24Fixtures.pages;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * SYNC-1 목록 전체 수집, SYNC-5 검사 실패율 (통합).
 * 공공 API는 WireMock이 흉내낸다. perPage=100 (SyncIntegrationTestSupport).
 * 서비스 키에 일부러 + / = 를 넣어, URL 인코딩해서 보내는지도 함께 본다.
 */
class FullSyncIntegrationTest extends SyncIntegrationTestSupport {

    static final String LIST_PATH = "/api/gov24/v3/serviceList";
    static final String SERVICE_KEY = "ab+c/d==";
    static final WireMockServer GOV24 = new WireMockServer(options().dynamicPort());

    static {
        GOV24.start();
    }

    @DynamicPropertySource
    static void gov24(DynamicPropertyRegistry registry) {
        registry.add("subsidy.gov24.base-url", GOV24::baseUrl);
        registry.add("subsidy.gov24.service-key", () -> SERVICE_KEY);
    }

    @Autowired
    SyncService syncService;

    @Autowired
    SyncRunQueryService syncRunQueryService;

    @BeforeEach
    void resetGov24() {
        GOV24.resetAll();
    }

    /** 공공 API가 이번 실행에서 돌려줄 페이지들을 정한다. 서비스 키와 perPage=100이 맞을 때만 응답한다. */
    private void serve(Map<Integer, String> pageBodies) {
        GOV24.resetAll();
        pageBodies.forEach((no, body) -> GOV24.stubFor(get(urlPathEqualTo(LIST_PATH))
                .withQueryParam("page", equalTo(String.valueOf(no)))
                .withQueryParam("perPage", equalTo("100"))
                .withQueryParam("serviceKey", equalTo(SERVICE_KEY))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json;charset=UTF-8")
                        .withBody(body))));
    }

    /** 첫 실행으로 id(1)~id(count)를 미리 넣어 둔다. */
    private void seed(int count) {
        serve(pages(100, count, items(1, count)));
        assertThat(syncService.runFull().status()).isEqualTo(SyncRunStatus.SUCCEEDED);
    }

    /** id(1)~id(count) 중 rejectedNo 번째 건만 서비스명을 지운 목록. */
    private static List<Map<String, Object>> itemsWithoutNameAt(int count, int rejectedNo) {
        List<Map<String, Object>> list = items(1, count);
        list.get(rejectedNo - 1).remove("서비스명");
        return list;
    }

    @Test
    @DisplayName("SYNC-1-N1 빈 DB, totalCount=250, perPage=100 → 3페이지를 불러 250건 생성, SUCCEEDED")
    void n1_firstFullSync() {
        serve(pages(100, 250, items(1, 250)));

        SyncRunView run = syncService.runFull();

        assertThat(run.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(run.created()).isEqualTo(250);
        assertThat(benefitCount()).isEqualTo(250);
        GOV24.verify(3, getRequestedFor(urlPathEqualTo(LIST_PATH)));
        // 키 원문 ab+c/d== 는 ab%2Bc%2Fd%3D%3D 로 인코딩되어야 한다 (+가 공백으로 바뀌면 안 됨)
        GOV24.verify(3, getRequestedFor(urlMatching("(?i)" + LIST_PATH + "\\?.*serviceKey=ab%2Bc%2Fd%3D%3D(&.*)?")));
    }

    @Test
    @DisplayName("SYNC-1-N2 기존 250건 중 2건만 바뀜 → updated=2, unchanged=248")
    void n2_onlyChangedItemsAreUpdated() {
        seed(250);
        List<Map<String, Object>> next = items(1, 250);
        next.get(0).put("서비스명", "바뀐 이름 1");
        next.get(1).put("지원내용", "바뀐 지원내용 2");
        serve(pages(100, 250, next));

        SyncRunView run = syncService.runFull();

        assertThat(run.updated()).isEqualTo(2);
        assertThat(run.unchanged()).isEqualTo(248);
        assertThat(run.created()).isEqualTo(0);
        assertThat(benefitName(id(1))).isEqualTo("바뀐 이름 1");
    }

    @Test
    @DisplayName("SYNC-1-B3 totalCount=0, data=[] → 기존 데이터 그대로, FAILED SOURCE_EMPTY, 삭제 판정 안 함")
    void b3_emptySource() {
        seed(3);
        serve(Map.of(1, page(1, 100, 0, List.of())));

        SyncRunView run = syncService.runFull();

        assertThat(run.status()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(run.errorCode()).isEqualTo("SOURCE_EMPTY");
        assertNothingRemoved(3);
    }

    @Test
    @DisplayName("SYNC-1-E2 페이지를 넘기는 사이 원본이 바뀌어 모은 건수(249) ≠ totalCount(250) → PARTIAL, 삭제 판정 안 함, 차이 기록")
    void e2_collectedCountDiffersFromTotalCount() {
        seed(250);
        List<Map<String, Object>> shifted = items(1, 250);
        shifted.remove(200); // id(201)이 도중에 사라져 마지막 페이지가 49건
        serve(pages(100, 250, shifted));

        SyncRunView run = syncService.runFull();

        assertThat(run.status()).isEqualTo(SyncRunStatus.PARTIAL);
        assertThat(run.sourceTotalCount()).isEqualTo(250);
        assertThat(run.fetched()).isEqualTo(249);
        assertNothingRemoved(250);
    }

    @Test
    @DisplayName("SYNC-1-E4 1페이지에 data 칸이 없음 → PARTIAL 또는 FAILED, LIST 실패(page=1) 기록, 삭제 판정 안 함")
    void e4_noDataFieldNeverSucceeds() {
        seed(3);
        serve(Map.of(1, Gov24Fixtures.NO_DATA));

        SyncRunView run = syncService.runFull();

        assertThat(run.status()).isIn(SyncRunStatus.PARTIAL, SyncRunStatus.FAILED);
        assertThat(syncRunQueryService.failures(run.runId()))
                .extracting(SyncFailureView::stage, SyncFailureView::page)
                .contains(tuple(SyncStage.LIST, 1));
        assertNothingRemoved(3);
    }

    @Test
    @DisplayName("SYNC-1-M1 첫 배포: 마이그레이션이 테이블과 코드 사전 초기값을 만들고, 첫 FULL 실행이 전부 채운다")
    void m1_firstDeployment() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertThat(tables).contains("benefit", "benefit_condition", "condition_code",
                "sync_run", "sync_failure", "raw_payload", "sync_lock");

        // gov24-api.md 코드표: 성별2 + 나이2 + 중위소득5 + 생애·대상17 + 가구9 + 창업3 + 소상공인3 + 기업3 + 산업4 = 48
        assertThat(jdbc.queryForObject("select count(*) from condition_code where known", Integer.class))
                .isEqualTo(48);
        assertThat(jdbc.queryForMap("select group_name, label from condition_code where code = 'JA0327'"))
                .containsEntry("group_name", "생애·대상")
                .containsEntry("label", "구직자/실업자");

        serve(pages(100, 150, items(1, 150)));
        assertThat(syncService.runFull().status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(benefitCount()).isEqualTo(150);
    }

    @Test
    @DisplayName("SYNC-5-E7 필드 이름이 바뀌어 모든 건에 서비스ID가 없음 → 실패율 10% 초과, FAILED SCHEMA_CHANGED, 기존 데이터 유지")
    void e7_schemaChanged() {
        seed(20);
        List<Map<String, Object>> renamed = items(1, 20);
        renamed.forEach(it -> it.put("서비스아이디", it.remove("서비스ID")));
        serve(pages(100, 20, renamed));

        SyncRunView run = syncService.runFull();

        assertThat(run.status()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(run.errorCode()).isEqualTo("SCHEMA_CHANGED");
        assertNothingRemoved(20);
        assertThat(benefitName(id(1))).isEqualTo("서비스 1");
    }

    @Test
    @DisplayName("SYNC-5-E11 검사 실패가 10% 이하(20건 중 1건) → SUCCEEDED, failed=1, 실패 건은 missing_count를 늘리지 않고 sync_failure에 남김")
    void e11_fewValidationFailuresStillSucceed() {
        seed(20);
        serve(pages(100, 20, itemsWithoutNameAt(20, 20)));

        SyncRunView run = syncService.runFull();

        assertThat(run.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(run.failed()).isEqualTo(1);
        assertNothingRemoved(20);
        assertThat(benefitName(id(20))).isEqualTo("서비스 20");
        assertThat(syncRunQueryService.failures(run.runId()))
                .extracting(SyncFailureView::stage, SyncFailureView::serviceId)
                .containsExactly(tuple(SyncStage.VALIDATE, id(20)));
    }

    @Test
    @DisplayName("SYNC-5-B2 검사 실패율이 딱 10%(10건 중 1건)면 계속 → SUCCEEDED")
    void b2_failureRateExactly10PercentContinues() {
        serve(pages(100, 10, itemsWithoutNameAt(10, 10)));

        SyncRunView run = syncService.runFull();

        assertThat(run.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(run.fetched()).isEqualTo(10);
        assertThat(run.failed()).isEqualTo(1);
        assertThat(benefitCount()).isEqualTo(9);
    }

    @Test
    @DisplayName("SYNC-5-B2 검사 실패율이 10% 초과(9건 중 1건, 약 11%)면 FAILED SCHEMA_CHANGED이고 이번 실행에서는 아무것도 저장하지 않는다")
    void b2_failureRateOver10PercentFails() {
        serve(pages(100, 9, itemsWithoutNameAt(9, 9)));

        SyncRunView run = syncService.runFull();

        assertThat(run.status()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(run.errorCode()).isEqualTo("SCHEMA_CHANGED");
        assertThat(benefitCount()).isZero();
    }
}
