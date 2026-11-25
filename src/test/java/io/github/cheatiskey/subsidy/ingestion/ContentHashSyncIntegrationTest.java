package io.github.cheatiskey.subsidy.ingestion;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static io.github.cheatiskey.subsidy.ingestion.Gov24Fixtures.id;
import static io.github.cheatiskey.subsidy.ingestion.Gov24Fixtures.items;
import static io.github.cheatiskey.subsidy.ingestion.Gov24Fixtures.pages;
import static io.github.cheatiskey.subsidy.ingestion.JdbcTimes.toDb;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * SYNC-1-H 조회수를 내용 지문에서 빼기 (이슈 #5, 통합).
 * 공공 API는 WireMock이 흉내낸다 (perPage=100). 시계는 NOW로 고정이라, "어제" 상태는 JDBC로 시각을 앞당겨 만든다.
 * 배포 전 행(hash_version=1)은 정상 실행으로 넣은 뒤 JDBC로 옛 지문(ContentHashV1)·hash_version=1로 바꿔 만든다.
 * 쓰기 도중 실패(H11)는 BenefitWriter 스파이로 낸다 (SyncRunFailureIntegrationTest와 같은 방식).
 */
class ContentHashSyncIntegrationTest extends SyncIntegrationTestSupport {

    static final String LIST_PATH = "/api/gov24/v3/serviceList";
    static final WireMockServer GOV24 = new WireMockServer(options().dynamicPort());
    static final Instant YESTERDAY = NOW.minus(Duration.ofDays(1));

    static {
        GOV24.start();
    }

    @DynamicPropertySource
    static void gov24(DynamicPropertyRegistry registry) {
        registry.add("subsidy.gov24.base-url", GOV24::baseUrl);
    }

    @Autowired
    SyncService syncService;

    @MockitoSpyBean
    BenefitWriter benefitWriter;

    @BeforeEach
    void resetGov24() {
        GOV24.resetAll();
    }

    /** 공공 API가 이 목록(totalCount = 건수)을 돌려주게 하고 전체 동기화를 한 번 돌린다. */
    private SyncRunView run(List<Map<String, Object>> list) {
        GOV24.resetAll();
        pages(100, list.size(), list).forEach((no, body) -> GOV24.stubFor(get(urlPathEqualTo(LIST_PATH))
                .withQueryParam("page", equalTo(String.valueOf(no)))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json;charset=UTF-8")
                        .withBody(body))));
        return syncService.runFull();
    }

    /** 혜택 한 건 중 이 케이스들이 보는 칸. */
    record Row(String name, Long viewCount, String contentHash, Integer hashVersion, long version,
               Instant updatedAt, Instant lastSeenAt, String indexedHash) {
    }

    private Row row(String serviceId) {
        return jdbc.queryForObject("select * from benefit where service_id = ?", (rs, i) -> new Row(
                rs.getString("name"), rs.getObject("view_count", Long.class), rs.getString("content_hash"),
                rs.getObject("hash_version", Integer.class), rs.getLong("version"),
                JdbcTimes.fromDb(rs, "updated_at"), JdbcTimes.fromDb(rs, "last_seen_at"),
                rs.getString("indexed_hash")), serviceId);
    }

    private static List<NormalizedBenefit> normalize(List<Map<String, Object>> list) {
        return new BenefitNormalizer().normalize(list).accepted();
    }

    /** 원본 한 건을 지금 규칙으로 정리했을 때의 지문. */
    private static String newHash(Map<String, Object> rawItem) {
        return BenefitWriter.contentHash(normalize(List.of(rawItem)).get(0));
    }

    /** 모든 혜택의 수정·확인 시각을 어제로 돌린다 (지난 실행이 어제였던 것처럼). */
    private void markAllAsYesterday() {
        jdbc.update("update benefit set updated_at = ?, last_seen_at = ?", toDb(YESTERDAY), toDb(YESTERDAY));
    }

    /**
     * 이미 저장된 seeded 건들을 배포 전 행처럼 바꾼다: 옛 규칙 지문(조회수 포함), 주어진 hash_version(1 또는 null),
     * 수정·확인 시각은 어제. seeded는 저장할 때 쓴 원본 그대로여야 옛 지문이 실제와 같다.
     */
    private void markAsBeforeDeploy(List<Map<String, Object>> seeded, Integer hashVersion) {
        for (NormalizedBenefit b : normalize(seeded)) {
            jdbc.update("update benefit set content_hash = ?, hash_version = ?, updated_at = ?, last_seen_at = ?"
                            + " where service_id = ?",
                    ContentHashV1.of(b), hashVersion, toDb(YESTERDAY), toDb(YESTERDAY), b.serviceId());
        }
    }

    private int rowsNotOnVersion2() {
        return jdbc.queryForObject("select count(*) from benefit where hash_version is distinct from 2", Integer.class);
    }

    @Test
    @DisplayName("SYNC-1-H1 새 규칙으로 저장된 혜택의 조회수만 1000→1050 → unchanged. updated_at·version·content_hash 그대로, last_seen_at 오늘, 조회수 1050")
    void h1_viewCountOnlyChangeIsUnchangedButStored() {
        run(items(1, 1, 1000));
        markAllAsYesterday();
        Row before = row(id(1));

        SyncRunView run = run(items(1, 1, 1050));

        assertThat(run.unchanged()).isEqualTo(1);
        assertThat(run.updated()).isZero();
        Row after = row(id(1));
        assertThat(after.viewCount()).isEqualTo(1050L);
        assertThat(after.lastSeenAt()).isEqualTo(NOW);
        assertThat(after.updatedAt()).isEqualTo(YESTERDAY);
        assertThat(after.version()).isEqualTo(before.version());
        assertThat(after.contentHash()).isEqualTo(before.contentHash());
    }

    @Test
    @DisplayName("SYNC-1-H2 기존 250건 중 2건은 내용이 바뀌고 250건 모두 조회수가 바뀜 (이슈 #5 재현) → updated=2, unchanged=248")
    void h2_issue5Reproduction() {
        run(items(1, 250, 1000));
        List<Map<String, Object>> next = items(1, 250, 1050);
        next.get(0).put("서비스명", "바뀐 이름 1");
        next.get(1).put("지원내용", "바뀐 지원내용 2");

        SyncRunView run = run(next);

        assertThat(run.updated()).isEqualTo(2);
        assertThat(run.unchanged()).isEqualTo(248);
        assertThat(run.created()).isZero();
    }

    @Test
    @DisplayName("SYNC-1-H3 내용과 조회수가 함께 바뀜 → updated. 새 내용·새 조회수·새 지문·hash_version=2, version 1 오름")
    void h3_contentAndViewCountChanged() {
        run(items(1, 1, 1000));
        markAllAsYesterday();
        Row before = row(id(1));
        List<Map<String, Object>> next = items(1, 1, 1050);
        next.get(0).put("서비스명", "바뀐 이름 1");

        SyncRunView run = run(next);

        assertThat(run.updated()).isEqualTo(1);
        Row after = row(id(1));
        assertThat(after.name()).isEqualTo("바뀐 이름 1");
        assertThat(after.viewCount()).isEqualTo(1050L);
        assertThat(after.contentHash()).isEqualTo(newHash(next.get(0)));
        assertThat(after.hashVersion()).isEqualTo(2);
        assertThat(after.version()).isEqualTo(before.version() + 1);
        assertThat(after.updatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("SYNC-1-H4 처음 보는 혜택 → created. hash_version=2, 조회수 777 저장")
    void h4_newBenefit() {
        SyncRunView run = run(items(1, 1, 777));

        assertThat(run.created()).isEqualTo(1);
        Row created = row(id(1));
        assertThat(created.hashVersion()).isEqualTo(2);
        assertThat(created.viewCount()).isEqualTo(777L);
    }

    static List<Arguments> viewCountBoundaries() {
        return List.of(
                Arguments.of(null, 0, 0L),
                Arguments.of(0, null, null),
                Arguments.of(1234, "abc", null)); // "abc"는 해석 못 해 null (SYNC-5-B1)
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}, 저장 {2}")
    @MethodSource("viewCountBoundaries")
    @DisplayName("SYNC-1-H6 조회수가 null→0, 0→null, 1234→\"abc\" → unchanged, 저장된 조회수는 받은 값(0 또는 null)으로 덮어씀")
    void h6_viewCountBoundaryIsUnchangedButOverwritten(Object before, Object after, Long stored) {
        run(items(1, 1, before));

        SyncRunView run = run(items(1, 1, after));

        assertThat(run.unchanged()).isEqualTo(1);
        assertThat(run.updated()).isZero();
        assertThat(row(id(1)).viewCount()).isEqualTo(stored);
    }

    @Test
    @DisplayName("SYNC-1-H7 모든 칸이 지난 실행과 같음 → unchanged. updated_at·version·content_hash 그대로, last_seen_at만 오늘")
    void h7_identicalItemOnlyRefreshesLastSeen() {
        run(items(1, 1, 1000));
        markAllAsYesterday();
        Row before = row(id(1));

        SyncRunView run = run(items(1, 1, 1000));

        assertThat(run.unchanged()).isEqualTo(1);
        assertThat(run.updated()).isZero();
        Row after = row(id(1));
        assertThat(after.lastSeenAt()).isEqualTo(NOW);
        assertThat(after.updatedAt()).isEqualTo(YESTERDAY);
        assertThat(after.version()).isEqualTo(before.version());
        assertThat(after.contentHash()).isEqualTo(before.contentHash());
    }

    @Test
    @DisplayName("SYNC-1-H8 배포 뒤 첫 실행, hash_version=1(과 null)인 행의 내용이 조회수 말고 같음 → 이번에 한 번 updated. 새 지문·hash_version=2, updated_at·version 오름")
    void h8_firstRunAfterDeployUpdatesOldRowsOnce() {
        run(items(1, 2, 1000));
        markAsBeforeDeploy(items(1, 1, 1000), 1);
        markAsBeforeDeploy(items(2, 2, 1000), null); // hash_version이 비어 있는 행도 옛 버전으로 본다 (SYNC-1-H 결정)
        long versionBefore = row(id(1)).version();
        List<Map<String, Object>> next = items(1, 2, 1050);

        SyncRunView run = run(next);

        assertThat(run.updated()).isEqualTo(2);
        assertThat(run.unchanged()).isZero();
        for (Map<String, Object> item : next) {
            Row after = row((String) item.get("서비스ID"));
            assertThat(after.hashVersion()).isEqualTo(2);
            assertThat(after.contentHash()).isEqualTo(newHash(item));
            assertThat(after.updatedAt()).isEqualTo(NOW);
            assertThat(after.version()).isEqualTo(versionBefore + 1);
            assertThat(after.viewCount()).isEqualTo(1050L);
        }
    }

    @Test
    @DisplayName("SYNC-1-H9 배포 뒤 첫 실행, hash_version=1인 행 중 내용이 실제로 바뀐 건 → updated. 새 내용·새 지문·hash_version=2")
    void h9_firstRunAfterDeployWithRealContentChange() {
        run(items(1, 2, 1000));
        markAsBeforeDeploy(items(1, 2, 1000), 1);
        List<Map<String, Object>> next = items(1, 2, 1050);
        next.get(0).put("서비스명", "바뀐 이름 1");

        SyncRunView run = run(next);

        assertThat(run.updated()).isEqualTo(2);
        Row changed = row(id(1));
        assertThat(changed.name()).isEqualTo("바뀐 이름 1");
        assertThat(changed.contentHash()).isEqualTo(newHash(next.get(0)));
        assertThat(changed.hashVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("SYNC-1-H10 배포 뒤 첫 실행을 마치고 같은 날 다시 실행, 그사이 조회수만 또 바뀜 → 전부 unchanged, hash_version=1인 행 0개")
    void h10_secondRunAfterDeployIsUnchanged() {
        run(items(1, 3, 1000));
        markAsBeforeDeploy(items(1, 3, 1000), 1);
        run(items(1, 3, 1050)); // 배포 뒤 첫 실행

        SyncRunView again = run(items(1, 3, 1100));

        assertThat(again.updated()).isZero();
        assertThat(again.unchanged()).isEqualTo(3);
        assertThat(rowsNotOnVersion2()).isZero();
    }

    @Test
    @DisplayName("SYNC-1-H11 배포 뒤 첫 실행이 DB에 다 쓴 뒤 예외로 끝남 → 되돌려져 모든 행이 hash_version=1·옛 지문 그대로, 다음 성공 실행이 hash_version=2로 바꿈")
    void h11_failedFirstRunRollsBackThenNextRunMigrates() {
        List<Map<String, Object>> seeded = items(1, 3, 1000);
        run(seeded);
        markAsBeforeDeploy(seeded, 1);
        DataAccessResourceFailureException error = new DataAccessResourceFailureException("DB 연결 끊김");
        // 실제로 모든 행을 쓴 다음에 실패시킨다: 되돌리기가 없으면 hash_version=2 행이 남는다.
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw error;
        }).when(benefitWriter).write(any(), any());

        assertThat(catchThrowable(() -> run(items(1, 3, 1050)))).isSameAs(error);

        for (NormalizedBenefit b : normalize(seeded)) {
            Row kept = row(b.serviceId());
            assertThat(kept.hashVersion()).isEqualTo(1);
            assertThat(kept.contentHash()).isEqualTo(ContentHashV1.of(b));
            assertThat(kept.viewCount()).isEqualTo(1000L);
        }

        doCallRealMethod().when(benefitWriter).write(any(), any());
        SyncRunView retry = run(items(1, 3, 1050));

        assertThat(retry.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(retry.updated()).isEqualTo(3);
        assertThat(rowsNotOnVersion2()).isZero();
    }

    @Test
    @DisplayName("SYNC-1-H12 배포 뒤 첫 실행 때 원본에 없어 hash_version=1로 남은 행이 나중에 다시 나타남 → 그 실행에서 updated, 새 지문·hash_version=2, 오류 없음")
    void h12_oldRowReappearsLater() {
        run(items(1, 3, 1000));
        markAsBeforeDeploy(items(1, 3, 1000), 1);
        run(items(1, 2, 1000)); // 배포 뒤 첫 실행: id(3)은 원본에 없음
        assertThat(row(id(3)).hashVersion()).isEqualTo(1);

        List<Map<String, Object>> later = items(1, 3, 1000);
        SyncRunView run = run(later);

        assertThat(run.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(run.updated()).isEqualTo(1);
        assertThat(run.unchanged()).isEqualTo(2);
        Row reappeared = row(id(3));
        assertThat(reappeared.hashVersion()).isEqualTo(2);
        assertThat(reappeared.contentHash()).isEqualTo(newHash(later.get(2)));
    }

    @Test
    @DisplayName("SYNC-1-H13 indexed_hash는 읽지도 쓰지도 않는다: 새 건은 null, 기존 건(내용 바뀜·조회수만 바뀜·옛 버전)은 넣어 둔 값 그대로")
    void h13_indexedHashIsUntouched() {
        run(items(1, 3, 1000));
        markAsBeforeDeploy(items(3, 3, 1000), 1);
        String indexed = "a".repeat(64);
        jdbc.update("update benefit set indexed_hash = ?", indexed);
        List<Map<String, Object>> next = items(1, 4, 1050);
        next.get(0).put("서비스명", "바뀐 이름 1"); // id(1) 내용 바뀜, id(2) 조회수만, id(3) 옛 버전, id(4) 새 건

        run(next);

        assertThat(List.of(id(1), id(2), id(3))).allSatisfy(id -> assertThat(row(id).indexedHash()).isEqualTo(indexed));
        assertThat(row(id(4)).indexedHash()).isNull();
    }
}
