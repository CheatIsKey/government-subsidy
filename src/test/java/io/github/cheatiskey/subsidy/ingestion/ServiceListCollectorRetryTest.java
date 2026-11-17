package io.github.cheatiskey.subsidy.ingestion;

import static io.github.cheatiskey.subsidy.ingestion.Gov24Fixtures.items;
import static io.github.cheatiskey.subsidy.ingestion.Gov24Fixtures.page;
import static io.github.cheatiskey.subsidy.ingestion.Gov24Fixtures.pages;
import static io.github.cheatiskey.subsidy.ingestion.ServiceKeyForms.KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SYNC-6 1차: 목록 한 페이지 호출의 재시도 (단위). 기다림은 가짜 sleeper, 무작위 지연은 고정 난수.
 * 기본 설정: 최대 4번, 간격 1s·4s·16s, 무작위 지연은 상수 20%(간격 × (1 + 0.2 × 난수), 난수 [0,1)). 목록은 totalCount=250, perPage=100 (3페이지).
 */
class ServiceListCollectorRetryTest {

    static final String LIST_PATH = "/api/gov24/v3/serviceList";
    static final Gov24RetryProperties DEFAULT_RETRY = new Gov24RetryProperties(
            4, List.of(Duration.ofSeconds(1), Duration.ofSeconds(4), Duration.ofSeconds(16)));

    private final ScriptedServiceListSource source = new ScriptedServiceListSource();
    private final List<Duration> slept = new ArrayList<>();
    private final List<CollectedPage> notified = new ArrayList<>();

    private ListCollectionResult collect(Gov24RetryProperties retry, double random) {
        ServiceListCollector collector = new ServiceListCollector(source, retry, slept::add, () -> random);
        AtomicReference<ListCollectionResult> result = new AtomicReference<>();
        assertThatCode(() -> result.set(collector.collect(100, notified::add)))
                .as("공공 API 실패에서 수집은 예외를 던지지 않는다").doesNotThrowAnyException();
        return result.get();
    }

    private ListCollectionResult collect() {
        return collect(DEFAULT_RETRY, 0.0);
    }

    /** 1·3페이지는 정상, 2페이지는 정해 준 결과. */
    private void secondPage(Object... outcome) {
        source.with(pages(100, 250, items(1, 250))).on(2, outcome);
    }

    private static Gov24ApiException http(int status, int page) {
        return Gov24Errors.httpError(status, LIST_PATH, page, "<html>error</html>", KEY);
    }

    private CollectedPage notifiedPage(int page) {
        return notified.stream().filter(p -> p.page() == page).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("SYNC-6-B1 503 3번 뒤 4번째에 200 → 성공 처리, 실패 기록 없음, 그 페이지 호출 4번")
    void b1_successOnLastAttempt() {
        String ok = page(2, 100, 250, items(101, 200));
        secondPage(http(503, 2), http(503, 2), http(503, 2), ok);

        ListCollectionResult result = collect();

        assertThat(source.calls(2)).isEqualTo(4);
        assertThat(result.failedPages()).isEmpty();
        assertThat(result.items()).hasSize(250);
        assertThat(notified).extracting(CollectedPage::failed).containsOnly(false);
    }

    @Test
    @DisplayName("SYNC-6-B2 4번 모두 503 → 정확히 4번 부르고 5번째는 없음, 그 페이지 실패 기록(503, HTTP_ERROR, 4번)")
    void b2_givesUpAfterFourAttempts() {
        secondPage(http(503, 2));

        ListCollectionResult result = collect();

        assertThat(source.calls(2)).isEqualTo(4);
        assertThat(result.failedPages()).containsExactly(2);
        assertThat(notifiedPage(2).failure()).isEqualTo(new PageFailure(503, "HTTP_ERROR", 4));
    }

    @Test
    @DisplayName("SYNC-6-B3 무작위 지연 0이면 기다림은 1s, 4s, 16s 순서이고 마지막 실패 뒤에는 기다리지 않는다")
    void b3_waitsOneFourSixteenSeconds() {
        secondPage(http(503, 2));

        collect(DEFAULT_RETRY, 0.0);

        assertThat(slept).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(4), Duration.ofSeconds(16));
    }

    @Test
    @DisplayName("SYNC-6-B3 난수 0.5면 간격 × (1 + 0.2 × 0.5) = 1.1s, 4.4s, 17.6s를 기다린다")
    void b3_jitterIsProportionalToInterval() {
        secondPage(http(503, 2));

        collect(DEFAULT_RETRY, 0.5);

        assertThat(slept).hasSize(3);
        assertThat(slept.get(0).toMillis()).isCloseTo(1100L, within(1L));
        assertThat(slept.get(1).toMillis()).isCloseTo(4400L, within(1L));
        assertThat(slept.get(2).toMillis()).isCloseTo(17600L, within(1L));
    }

    @Test
    @DisplayName("SYNC-6-B3 난수가 1에 가까워도(0.999) 간격 × (1 + 0.2 × 0.999): 각 간격의 20% 미만만 더한다")
    void b3_jitterStaysBelowTwentyPercent() {
        secondPage(http(503, 2));

        collect(DEFAULT_RETRY, 0.999);

        assertThat(slept).hasSize(3);
        assertThat(slept.get(0)).isGreaterThan(Duration.ofMillis(1190)).isLessThan(Duration.ofMillis(1200));
        assertThat(slept.get(1)).isGreaterThan(Duration.ofMillis(4790)).isLessThan(Duration.ofMillis(4800));
        assertThat(slept.get(2)).isGreaterThan(Duration.ofMillis(19160)).isLessThan(Duration.ofMillis(19200));
    }

    @Test
    @DisplayName("SYNC-6-B3 두 번째 호출에서 성공하면 기다림은 1s 한 번뿐")
    void b3_noMoreWaitAfterSuccess() {
        secondPage(http(503, 2), page(2, 100, 250, items(101, 200)));

        collect();

        assertThat(slept).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("SYNC-6-B4 설정을 바꾸면(횟수 2, 간격 0s) 그 값이 쓰인다: 2번 부르고, 0초 간격도 sleeper를 1번 부르고, 실패 기록 attempt=2")
    void b4_configuredAttemptsAreUsed() {
        secondPage(http(503, 2));

        ListCollectionResult result = collect(new Gov24RetryProperties(2, List.of(Duration.ZERO)), 0.5);

        assertThat(source.calls(2)).isEqualTo(2);
        assertThat(result.failedPages()).containsExactly(2);
        assertThat(notifiedPage(2).failure().attemptCount()).isEqualTo(2);
        assertThat(slept).containsExactly(Duration.ZERO);
    }

    @Test
    @DisplayName("SYNC-6-B4 간격이 (횟수−1)개보다 많으면(횟수 2, 간격 0s·5s·9s) 남는 간격은 무시: 2번 부르고 기다림은 0s 한 번")
    void b4_extraIntervalsAreIgnored() {
        secondPage(http(503, 2));

        collect(new Gov24RetryProperties(2, List.of(Duration.ZERO, Duration.ofSeconds(5), Duration.ofSeconds(9))), 0.0);

        assertThat(source.calls(2)).isEqualTo(2);
        assertThat(slept).containsExactly(Duration.ZERO);
    }

    @Test
    @DisplayName("SYNC-6-B4 횟수 1(간격 1s)이면 재시도 없음: 1번 부르고 기다리지 않고 실패 기록 attempt=1")
    void b4_singleAttemptMeansNoRetry() {
        secondPage(http(503, 2));

        ListCollectionResult result = collect(new Gov24RetryProperties(1, List.of(Duration.ofSeconds(1))), 0.0);

        assertThat(source.calls(2)).isEqualTo(1);
        assertThat(slept).isEmpty();
        assertThat(result.failedPages()).containsExactly(2);
        assertThat(notifiedPage(2).failure()).isEqualTo(new PageFailure(503, "HTTP_ERROR", 1));
    }

    @ParameterizedTest(name = "[{index}] HTTP {0}")
    @ValueSource(ints = {408, 429})
    @DisplayName("SYNC-6-F2 408·429 4번 연속 → 재시도 대상: 호출 4번, 그 페이지 실패 기록(408|429, HTTP_ERROR, 4번), 3페이지 계속")
    void f2_requestTimeoutAndTooManyRequestsAreRetried(int status) {
        secondPage(http(status, 2));

        ListCollectionResult result = collect();

        assertThat(source.calls(2)).isEqualTo(4);
        assertThat(source.calls(3)).isEqualTo(1);
        assertThat(result.failedPages()).containsExactly(2);
        assertThat(notifiedPage(2).failure()).isEqualTo(new PageFailure(status, "HTTP_ERROR", 4));
    }

    @Test
    @DisplayName("SYNC-6-F12 (단위) 공공 API가 아닌 RuntimeException → 재시도 없이(호출 1번, 기다림 없음) 같은 예외를 그대로 전파")
    void f12_nonApiExceptionPropagatesWithoutRetry() {
        IllegalStateException error = new IllegalStateException("예상 못 한 오류");
        secondPage(error);
        ServiceListCollector collector = new ServiceListCollector(source, DEFAULT_RETRY, slept::add, () -> 0.0);

        Throwable thrown = catchThrowable(() -> collector.collect(100, notified::add));

        assertThat(thrown).isSameAs(error);
        assertThat(source.calls(2)).isEqualTo(1);
        assertThat(slept).isEmpty();
    }

    @Test
    @DisplayName("SYNC-6-F4 중간 페이지가 연결 실패 4번 → 호출 4번, 실패 기록(http_status 없음, CONNECTION, 4번), 3페이지 계속")
    void f4_connectionFailureIsRetried() {
        secondPage(Gov24Errors.ioError(new ConnectException("Connection refused"), LIST_PATH, 2, KEY));

        ListCollectionResult result = collect();

        assertThat(source.calls(2)).isEqualTo(4);
        assertThat(source.calls(3)).isEqualTo(1);
        assertThat(result.failedPages()).containsExactly(2);
        assertThat(notifiedPage(2).failure()).isEqualTo(new PageFailure(null, "CONNECTION", 4));
    }

    @Test
    @DisplayName("SYNC-6-F4 중간 페이지가 연결 뒤 끊김(IO) 4번 → 호출 4번, 실패 기록(http_status 없음, IO, 4번), 3페이지 계속")
    void f4_ioFailureIsRetried() {
        secondPage(Gov24Errors.ioError(new IOException("Connection reset"), LIST_PATH, 2, KEY));

        ListCollectionResult result = collect();

        assertThat(source.calls(2)).isEqualTo(4);
        assertThat(source.calls(3)).isEqualTo(1);
        assertThat(result.failedPages()).containsExactly(2);
        assertThat(notifiedPage(2).failure()).isEqualTo(new PageFailure(null, "IO", 4));
    }

    @Test
    @DisplayName("SYNC-6-F4 상태(502)를 받은 뒤 본문 읽기 실패(IO) 4번 → http_status는 예외에 있는 502를 기록")
    void f4_ioFailureAfterStatusKeepsStatus() {
        secondPage(Gov24Errors.ioErrorAfterStatus(new IOException("truncated"), 502, LIST_PATH, 2, KEY));

        collect();

        assertThat(source.calls(2)).isEqualTo(4);
        assertThat(notifiedPage(2).failure()).isEqualTo(new PageFailure(502, "IO", 4));
    }

    @Test
    @DisplayName("SYNC-6-F11b 404 상태를 받은 뒤 본문 읽기 실패(종류 IO) → 상태 코드를 먼저 봄: 재시도 없음(호출 1번), 그 페이지만 실패 (404, IO, 1번), 3페이지 계속")
    void f11b_clientErrorStatusWithIoFailureIsNotRetried() {
        secondPage(Gov24Errors.ioErrorAfterStatus(new IOException("truncated"), 404, LIST_PATH, 2, KEY));

        ListCollectionResult result = collect();

        assertThat(source.calls(2)).isEqualTo(1);
        assertThat(source.calls(3)).isEqualTo(1);
        assertThat(slept).isEmpty();
        assertThat(result.failedPages()).containsExactly(2);
        assertThat(notifiedPage(2).failure()).isEqualTo(new PageFailure(404, "IO", 1));
    }

    @Test
    @DisplayName("SYNC-6-F11b (짝) 503 상태를 받은 뒤 본문 읽기 실패(종류 IO) → 재시도 대상: 호출 4번, 실패 (503, IO, 4번), 3페이지 계속")
    void f11b_serverErrorStatusWithIoFailureIsRetried() {
        secondPage(Gov24Errors.ioErrorAfterStatus(new IOException("truncated"), 503, LIST_PATH, 2, KEY));

        ListCollectionResult result = collect();

        assertThat(source.calls(2)).isEqualTo(4);
        assertThat(source.calls(3)).isEqualTo(1);
        assertThat(result.failedPages()).containsExactly(2);
        assertThat(notifiedPage(2).failure()).isEqualTo(new PageFailure(503, "IO", 4));
    }

    @ParameterizedTest(name = "[{index}] HTTP {0}")
    @ValueSource(ints = {400, 404})
    @DisplayName("SYNC-6-F11 401·403을 뺀 4xx → 재시도 없음(호출 1번), 그 페이지만 실패 기록(attempt=1) 후 3페이지 계속")
    void f11_otherClientErrorIsNotRetried(int status) {
        secondPage(http(status, 2));

        ListCollectionResult result = collect();

        assertThat(source.calls(2)).isEqualTo(1);
        assertThat(source.calls(3)).isEqualTo(1);
        assertThat(slept).isEmpty();
        assertThat(result.failedPages()).containsExactly(2);
        assertThat(notifiedPage(2).failure()).isEqualTo(new PageFailure(status, "HTTP_ERROR", 1));
    }

    @Test
    @DisplayName("SYNC-6-F11-403 403 → 재시도 없음(호출 1번), 기다리지 않음, 수집을 멈춰 3페이지를 부르지 않는다")
    void f11_403_forbiddenStopsWithoutRetry() {
        secondPage(http(403, 2));

        collect();

        assertThat(source.calls(2)).isEqualTo(1);
        assertThat(slept).isEmpty();
        assertThat(source.requestedPages).containsExactly(1, 2);
    }

    @ParameterizedTest(name = "[{index}] HTTP {0}")
    @ValueSource(ints = {401, 403})
    @DisplayName("SYNC-6-F1b 401·403 상태를 받은 뒤 본문 읽기 실패(종류 IO) → 상태 코드를 먼저 봄: 재시도 없음(호출 1번), 멈춤 AUTH_FAILED, 실패 (401|403, IO, 1번)")
    void f1b_authStatusWithIoFailureStopsWithoutRetry(int status) {
        secondPage(Gov24Errors.ioErrorAfterStatus(new IOException("truncated"), status, LIST_PATH, 2, KEY));

        ListCollectionResult result = collect();

        assertThat(source.calls(2)).isEqualTo(1);
        assertThat(slept).isEmpty();
        assertThat(source.requestedPages).containsExactly(1, 2);
        assertThat(result.stop().errorCode()).isEqualTo("AUTH_FAILED");
        assertThat(notifiedPage(2).failure()).isEqualTo(new PageFailure(status, "IO", 1));
    }

    @Test
    @DisplayName("SYNC-6-F10b (회귀) 1페이지 404 → 재시도 없음(호출 1번), 전체 건수를 몰라 멈춤 SOURCE_UNAVAILABLE, 실패 1건 (404, HTTP_ERROR, 1번)")
    void f10b_firstPageNotFoundStopsAsSourceUnavailable() {
        source.on(1, http(404, 1));

        ListCollectionResult result = collect();

        assertThat(source.requestedPages).containsExactly(1);
        assertThat(slept).isEmpty();
        assertThat(result.stop().errorCode()).isEqualTo("SOURCE_UNAVAILABLE");
        assertThat(result.failedPages()).containsExactly(1);
        assertThat(notified).extracting(CollectedPage::page, CollectedPage::failure)
                .containsExactly(tuple(1, new PageFailure(404, "HTTP_ERROR", 1)));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"no-data", "<html><body>Service Unavailable</body></html>"})
    @DisplayName("SYNC-6-E2 (회귀) 200인데 data가 없거나 JSON이 아님 → 재시도하지 않고(호출 1번) 그 페이지만 실패 기록 후 계속")
    void e2_invalidBodyWithHttp200IsNotRetried(String body) {
        secondPage(body.equals("no-data") ? Gov24Fixtures.NO_DATA : body);

        ListCollectionResult result = collect();

        assertThat(source.calls(2)).isEqualTo(1);
        assertThat(source.calls(3)).isEqualTo(1);
        assertThat(slept).isEmpty();
        assertThat(result.failedPages()).containsExactly(2);
    }
}
