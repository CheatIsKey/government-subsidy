package io.github.cheatiskey.subsidy.ingestion;

import static io.github.cheatiskey.subsidy.ingestion.Gov24Fixtures.id;
import static io.github.cheatiskey.subsidy.ingestion.Gov24Fixtures.item;
import static io.github.cheatiskey.subsidy.ingestion.Gov24Fixtures.items;
import static io.github.cheatiskey.subsidy.ingestion.Gov24Fixtures.pages;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * SYNC-9 실행 기록 (통합). 운영 API는 local 프로필에서만 켜진다 (Q-17).
 * 공공 API 대신 페이지 본문을 직접 정해 주는 가짜 목록 공급자를 쓴다 (실행 도중 멈추게 하려고).
 * SyncService는 생성자로 ServiceListSource를 받으므로 @Primary 가짜가 끼워진다.
 */
@ActiveProfiles("local")
@AutoConfigureMockMvc
class SyncRunRecordIntegrationTest extends SyncIntegrationTestSupport {

    @Autowired
    SyncService syncService;

    @Autowired
    SyncRunQueryService syncRunQueryService;

    @Autowired
    ControllableServiceListSource source;

    @Autowired
    MockMvcTester mvc;

    /** 백그라운드에서 돌린 실행. 다음 테스트가 DB를 지우기 전에 반드시 끝나 있어야 한다. */
    private CompletableFuture<SyncRunView> background;

    @AfterEach
    void finishBackgroundRun() {
        source.release();
        if (background != null) {
            background.handle((run, error) -> null).orTimeout(30, TimeUnit.SECONDS).join();
        }
    }

    /** 1차 실행: id(1)~id(20) 새로 20건. */
    private SyncRunView firstRun() {
        source.serve(pages(100, 20, items(1, 20)));
        return syncService.runFull();
    }

    /** 2차 실행: 18건 그대로, id(19) 바뀜, id(20) 안 옴, id(21) 새로, id(22)는 서비스명 없음(실패). 실패율 1/21은 10% 이하. */
    private SyncRunView secondRun() {
        List<Map<String, Object>> next = items(1, 19);
        next.get(18).put("서비스명", "바뀐 이름 19");
        next.add(item(id(21), "서비스 21"));
        Map<String, Object> noName = item(id(22), "서비스 22");
        noName.remove("서비스명");
        next.add(noName);
        source.serve(pages(100, 21, next));
        return syncService.runFull();
    }

    /** DB에 남은 실행 기록을 다시 읽는다. */
    private SyncRunView recorded(long runId) {
        Optional<SyncRunView> found = syncRunQueryService.find(runId);
        assertThat(found).as("runId=" + runId + " 기록").isPresent();
        return found.get();
    }

    @Test
    @DisplayName("SYNC-9-N1 실행이 끝나면 종류·상태·시작·끝 시각과 source_total_count가 기록된다")
    void n1_finishedRunRecordsTimesAndStatus() {
        long runId = firstRun().runId();

        SyncRunView run = recorded(runId);
        assertThat(run.type()).isEqualTo(SyncType.FULL);
        assertThat(run.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(run.startedAt()).isEqualTo(NOW);
        assertThat(run.finishedAt()).isEqualTo(NOW);
        assertThat(run.sourceTotalCount()).isEqualTo(20);
    }

    @Test
    @DisplayName("SYNC-9-N1 실행이 끝나면 건수 6종(받음·추가·갱신·그대로·삭제·실패)이 기록된다")
    void n1_finishedRunRecordsSixCounts() {
        firstRun();
        long runId = secondRun().runId();

        SyncRunView run = recorded(runId);
        assertThat(run.sourceTotalCount()).isEqualTo(21);
        assertThat(List.of(run.fetched(), run.created(), run.updated(), run.unchanged(), run.removed(), run.failed()))
                .as("받음, 추가, 갱신, 그대로, 삭제, 실패")
                .containsExactly(21, 1, 1, 18, 0, 1);
    }

    @Test
    @DisplayName("SYNC-9-N2 운영 API로 실행 목록(최신순), 단건, 실패 목록을 조회한다")
    void n2_opsApiReturnsRunsAndFailures() {
        long firstId = firstRun().runId();
        long runId = secondRun().runId();

        assertThat(mvc.get().uri("/ops/v1/sync-runs?size=20")).hasStatusOk()
                .bodyJson().extractingPath("$.items[*].runId").asArray()
                .containsExactly((int) runId, (int) firstId);

        assertThat(mvc.get().uri("/ops/v1/sync-runs/{runId}", runId)).hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.runId", v -> v.assertThat().isEqualTo((int) runId))
                .hasPathSatisfying("$.type", v -> v.assertThat().isEqualTo("FULL"))
                .hasPathSatisfying("$.sourceTotalCount", v -> v.assertThat().isEqualTo(21))
                .hasPathSatisfying("$.failed", v -> v.assertThat().isEqualTo(1));

        assertThat(mvc.get().uri("/ops/v1/sync-runs/{runId}/failures", runId)).hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.items[*].stage", v -> v.assertThat().asArray().containsExactly("VALIDATE"))
                .hasPathSatisfying("$.items[*].serviceId", v -> v.assertThat().asArray().containsExactly(id(22)));
    }

    @Test
    @DisplayName("SYNC-9-E1 실행 중에 조회하면 RUNNING과 지금까지의 건수(1페이지 100건)를 돌려준다")
    void e1_queryWhileRunning() {
        source.serve(pages(100, 250, items(1, 250)));
        source.blockAtPage(2);

        background = CompletableFuture.supplyAsync(syncService::runFull);

        assertThat(source.awaitBlocked()).as("실행이 2페이지 요청에 도달").isTrue();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(mvc.get().uri("/ops/v1/sync-runs?size=20")).hasStatusOk()
                        .bodyJson()
                        .hasPathSatisfying("$.items[0].status", v -> v.assertThat().isEqualTo("RUNNING"))
                        .hasPathSatisfying("$.items[0].fetched", v -> v.assertThat().isEqualTo(100)));

        source.release();
        assertThat(background.orTimeout(30, TimeUnit.SECONDS).join().status()).isEqualTo(SyncRunStatus.SUCCEEDED);
    }

    /** 페이지 본문을 테스트가 정하고, 원하면 특정 페이지 요청에서 멈춰 세우는 가짜 목록 공급자. */
    static class ControllableServiceListSource implements ServiceListSource {

        private final Map<Integer, String> bodies = new ConcurrentHashMap<>();
        private volatile int gatedPage = -1;
        private volatile CountDownLatch gate = new CountDownLatch(0);
        private volatile CountDownLatch blocked = new CountDownLatch(1);

        void serve(Map<Integer, String> pages) {
            bodies.clear();
            bodies.putAll(pages);
        }

        void blockAtPage(int page) {
            gate = new CountDownLatch(1);
            blocked = new CountDownLatch(1);
            gatedPage = page;
        }

        boolean awaitBlocked() {
            try {
                return blocked.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        void release() {
            gatedPage = -1;
            gate.countDown();
        }

        @Override
        public String fetchPage(int page, int perPage) {
            if (page == gatedPage) {
                blocked.countDown();
                try {
                    gate.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            String body = bodies.get(page);
            if (body == null) {
                throw new AssertionError("정하지 않은 페이지를 불렀다: page=" + page);
            }
            return body;
        }
    }

    @TestConfiguration
    static class SourceConfig {

        @Bean
        @Primary
        ControllableServiceListSource controllableServiceListSource() {
            return new ControllableServiceListSource();
        }
    }
}
