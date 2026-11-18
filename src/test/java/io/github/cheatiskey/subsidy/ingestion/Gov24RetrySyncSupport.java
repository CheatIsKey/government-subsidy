package io.github.cheatiskey.subsidy.ingestion;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.github.cheatiskey.subsidy.ingestion.ServiceKeyForms.KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.tuple;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.LoggerFactory;

/**
 * SYNC-6 재시도 통합 테스트 공통 (WireMock). 목록은 totalCount=250, perPage=100 (3페이지).
 * 재시도 횟수·간격과 타임아웃은 하위 클래스가 @TestPropertySource로 정한다(간격은 0s로 둔다).
 */
abstract class Gov24RetrySyncSupport extends Gov24StubbedSyncSupport {

    /** 실행 전에 넣어 둔 혜택: 2페이지에 해당하는 id(101)~id(103). 실패 실행이 이것을 지우거나 바꾸면 안 된다. */
    void seedThreeBenefitsOfPageTwo() {
        okPage(1, 3, 101, 103);
        assertThat(syncService.runFull().status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        GOV24.resetAll();
    }

    static ResponseDefinitionBuilder status(int status) {
        return aResponse().withStatus(status).withBody("<html>error " + KEY + "</html>");
    }

    /** 이 page 요청에 응답을 차례로 준다. 마지막 응답은 그 뒤로 계속 준다. */
    static void onPageInOrder(int page, ResponseDefinitionBuilder... responses) {
        for (int i = 0; i < responses.length; i++) {
            var stub = get(urlPathEqualTo(LIST_PATH)).withQueryParam("page", equalTo(String.valueOf(page)))
                    .inScenario("page-" + page)
                    .whenScenarioStateIs(i == 0 ? Scenario.STARTED : "call-" + i)
                    .willReturn(responses[i]);
            GOV24.stubFor(i < responses.length - 1 ? stub.willSetStateTo("call-" + (i + 1)) : stub);
        }
    }

    static int calls(int page) {
        return GOV24.findAll(getRequestedFor(urlPathEqualTo(LIST_PATH))
                .withQueryParam("page", equalTo(String.valueOf(page)))).size();
    }

    /** 공공 API 실패에서 runFull은 예외를 던지지 않고 끝난 실행 기록을 돌려준다. */
    SyncRunView runFullRecorded() {
        AtomicReference<SyncRunView> run = new AtomicReference<>();
        assertThatCode(() -> run.set(syncService.runFull()))
                .as("공공 API 실패에서 runFull은 예외를 던지지 않는다").doesNotThrowAnyException();
        return run.get();
    }

    /** sync_failure 행마다 stage, page, http_status, error_code, attempt_count, message. */
    List<Map<String, Object>> failureRows(long runId) {
        return jdbc.queryForList("""
                select stage, page, http_status, error_code, attempt_count, message
                  from sync_failure where run_id = ? order by id""", runId);
    }

    /** 실패 행이 정확히 이 한 줄 (stage, page, http_status, error_code, attempt_count). */
    void assertSingleFailureRow(long runId, int page, Integer httpStatus, String errorCode, int attempts) {
        assertThat(failureRows(runId))
                .extracting(r -> r.get("stage"), r -> r.get("page"), r -> r.get("http_status"),
                        r -> r.get("error_code"), r -> r.get("attempt_count"))
                .containsExactly(tuple("LIST", page, httpStatus, errorCode, attempts));
    }

    void assertNoRunLeftRunning() {
        assertThat(syncRunQueryService.list(20)).extracting(SyncRunView::status)
                .doesNotContain(SyncRunStatus.RUNNING);
    }

    /** 2페이지가 계속 이 응답 → attempts번 부르고 실패 기록, 3페이지 계속, PARTIAL, 삭제 판정 안 함. */
    void assertSecondPageGivesUp(ResponseDefinitionBuilder response, Integer httpStatus, String errorCode, int attempts) {
        seedThreeBenefitsOfPageTwo();
        okPage(1, 250, 1, 100);
        onPage(2, response);
        okPage(3, 250, 201, 250);

        SyncRunView run = runFullRecorded();

        assertThat(calls(2)).isEqualTo(attempts);
        assertThat(calls(3)).isEqualTo(1);
        assertSingleFailureRow(run.runId(), 2, httpStatus, errorCode, attempts);
        assertThat(run.status()).isEqualTo(SyncRunStatus.PARTIAL);
        assertNothingRemoved(153);
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

        /** WARN과 ERROR. */
        List<String> warnOrAbove() {
            return appender.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN)).map(Logs::line).toList();
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
