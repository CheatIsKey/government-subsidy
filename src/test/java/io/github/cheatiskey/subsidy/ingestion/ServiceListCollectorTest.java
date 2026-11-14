package io.github.cheatiskey.subsidy.ingestion;

import static io.github.cheatiskey.subsidy.ingestion.Gov24Fixtures.id;
import static io.github.cheatiskey.subsidy.ingestion.Gov24Fixtures.item;
import static io.github.cheatiskey.subsidy.ingestion.Gov24Fixtures.items;
import static io.github.cheatiskey.subsidy.ingestion.Gov24Fixtures.page;
import static io.github.cheatiskey.subsidy.ingestion.Gov24Fixtures.pages;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** SYNC-1 목록 전체 수집: 페이지 넘기기 (단위). */
class ServiceListCollectorTest {

    private final FakeServiceListSource source = new FakeServiceListSource();
    private final ServiceListCollector collector = new ServiceListCollector(source);
    private final List<CollectedPage> notified = new ArrayList<>();

    private ListCollectionResult collect(int perPage) {
        return collector.collect(perPage, notified::add);
    }

    @Test
    @DisplayName("SYNC-1-B1 totalCount가 perPage의 배수(2000/1000)면 정확히 2페이지만 부른다")
    void b1_totalCountIsMultipleOfPerPage() {
        source.with(pages(1000, 2000, items(1, 2000)));

        ListCollectionResult result = collect(1000);

        assertThat(source.requestedPages).containsExactly(1, 2);
        assertThat(source.requestedPerPages).containsOnly(1000);
        assertThat(result.items()).hasSize(2000);
        assertThat(result.failedPages()).isEmpty();
    }

    @Test
    @DisplayName("SYNC-1-B2 마지막 페이지가 perPage보다 적으면(250/100) 그 페이지에서 멈춘다")
    void b2_lastPageSmallerThanPerPage() {
        source.with(pages(100, 250, items(1, 250)));

        ListCollectionResult result = collect(100);

        assertThat(source.requestedPages).containsExactly(1, 2, 3);
        assertThat(result.items()).hasSize(250);
        assertThat(result.sourceTotalCount()).isEqualTo(250);
    }

    @Test
    @DisplayName("SYNC-1-E1 같은 서비스ID가 두 번 나오면 처음 나온 1건만 남기고 중복 건수를 센다")
    void e1_duplicateServiceIdKeepsFirst() {
        source.with(1, page(1, 100, 3, List.of(item(id(1), "처음"), item(id(2), "둘째"), item(id(1), "나중"))));

        ListCollectionResult result = collect(100);

        assertThat(result.items()).extracting(it -> it.get("서비스ID"), it -> it.get("서비스명"))
                .containsExactlyInAnyOrder(tuple(id(1), "처음"), tuple(id(2), "둘째"));
        assertThat(result.duplicateCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("SYNC-1-E3 totalCount>0인데 2페이지 data가 비었으면 2페이지를 실패로 기록하고 3페이지까지 계속 부른다")
    void e3_emptyDataInMiddlePage() {
        source.with(1, page(1, 100, 250, items(1, 100)))
              .with(2, page(2, 100, 250, List.of()))
              .with(3, page(3, 100, 250, items(201, 250)));

        ListCollectionResult result = collect(100);

        assertThat(source.requestedPages).containsExactly(1, 2, 3);
        assertThat(result.failedPages()).containsExactly(2);
        assertThat(result.items()).hasSize(150);
    }

    @Test
    @DisplayName("SYNC-1-E4 2페이지에 data 칸이 없으면 2페이지만 실패로 기록하고 3페이지까지 계속 부른다")
    void e4_noDataFieldInMiddlePage() {
        source.with(1, page(1, 100, 250, items(1, 100)))
              .with(2, Gov24Fixtures.NO_DATA)
              .with(3, page(3, 100, 250, items(201, 250)));

        ListCollectionResult result = collect(100);

        assertThat(source.requestedPages).containsExactly(1, 2, 3);
        assertThat(result.failedPages()).containsExactly(2);
        assertThat(result.items()).hasSize(150);
        assertThat(notified).extracting(CollectedPage::page, p -> p.items().size(), CollectedPage::failed)
                .containsExactly(tuple(1, 100, false), tuple(2, 0, true), tuple(3, 50, false));
    }

    @Test
    @DisplayName("SYNC-1-E4 1페이지에 data 칸이 없으면({\"code\":0,\"msg\":\"정상\"}) totalCount를 모르므로 실패로 기록하고 멈춘다")
    void e4_noDataFieldInFirstPageStops() {
        source.with(1, Gov24Fixtures.NO_DATA);

        ListCollectionResult result = collect(100);

        assertThat(source.requestedPages).containsExactly(1);
        assertThat(result.failedPages()).containsExactly(1);
        assertThat(result.items()).isEmpty();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "<html><body>Service Unavailable</body></html>",
            "{\"code\":-4,\"msg\":\"등록되지 않은 인증키 입니다.\"}"
    })
    @DisplayName("SYNC-5-E8 HTTP 200인데 본문이 JSON이 아니거나 에러 문구면 그 페이지를 실패로 처리한다")
    void e8_invalidBodyWithHttp200(String body) {
        source.with(1, page(1, 100, 150, items(1, 100)))
              .with(2, body);

        ListCollectionResult result = collect(100);

        assertThat(result.failedPages()).containsExactly(2);
        assertThat(result.items()).hasSize(100);
    }

    @Test
    @DisplayName("SYNC-9-E1 (보조) 수집기는 페이지를 하나 처리할 때마다 진행을 알린다")
    void syncRun_e1_notifiesEachPage() {
        source.with(pages(100, 250, items(1, 250)));

        collect(100);

        assertThat(notified).extracting(CollectedPage::page, p -> p.items().size(), CollectedPage::failed)
                .containsExactly(tuple(1, 100, false), tuple(2, 100, false), tuple(3, 50, false));
    }
}
