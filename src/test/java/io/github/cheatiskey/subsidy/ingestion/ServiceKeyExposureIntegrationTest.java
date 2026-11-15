package io.github.cheatiskey.subsidy.ingestion;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static io.github.cheatiskey.subsidy.ingestion.ServiceKeyForms.KEY;
import static io.github.cheatiskey.subsidy.ingestion.ServiceKeyForms.assertNoKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.charset.StandardCharsets;
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
 * OPS-5 서비스 키 노출 방어: 동기화 실행(runFull)과 운영 API까지 (통합).
 * HTTP 오류·정상 응답 시험이라 타임아웃은 넉넉하게 5초 (docs/decisions.md). 타임아웃 시험은 ServiceKeyTimeoutIntegrationTest.
 */
@ActiveProfiles("local")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
        "subsidy.gov24.connect-timeout=5s",
        "subsidy.gov24.read-timeout=5s"
})
class ServiceKeyExposureIntegrationTest extends Gov24StubbedSyncSupport {

    @Autowired
    MockMvcTester mvc;

    @Test
    @DisplayName("OPS-5-N2 공공 API가 3페이지에서 401 → 감싼 예외, 메시지에 status=401·경로·page=3, 키 없음")
    void n2_unauthorizedOnThirdPage() {
        okPage(1, 250, 1, 100);
        okPage(2, 250, 101, 200);
        onPage(3, aResponse().withStatus(401).withHeader("Content-Type", "application/json").withBody(KEY_ERROR_BODY));

        Gov24ApiException e = runFullFails();

        assertThat(e.httpStatus()).isEqualTo(401);
        assertThat(e.path()).isEqualTo(LIST_PATH);
        assertThat(e.page()).isEqualTo(3);
        assertThat(e.getMessage()).startsWith("HTTP_ERROR ").contains("status=401", "path=" + LIST_PATH, "page=3");
        assertNoKey(e.getMessage());
    }

    @Test
    @DisplayName("OPS-5-E8 (회귀) HTTP 200인데 본문이 JSON이 아니면 예외 없이 그 페이지를 고정 문구로 실패 기록")
    void e8_nonJsonBodyIsRecordedWithFixedMessage() {
        onPage(1, aResponse().withStatus(200).withHeader("Content-Type", "text/html")
                .withBody("<html>오류 " + KEY + "</html>"));

        SyncRunView run = syncService.runFull();

        assertThat(syncRunQueryService.failures(run.runId()))
                .extracting(SyncFailureView::stage, SyncFailureView::page, SyncFailureView::message)
                .containsExactly(tuple(SyncStage.LIST, 1, "목록 페이지 응답에 data가 없거나 해석할 수 없음"));
    }

    @ParameterizedTest(name = "[{index}] HTTP {0}")
    @ValueSource(ints = {401, 500})
    @DisplayName("OPS-5-E11 동기화 중 401/500 → 콘솔 전체와 스택 트레이스에 키 없음, 세 로거 로그 0건")
    void e11_httpErrorLeaksNoKey(int status, CapturedOutput output) {
        onPage(1, aResponse().withStatus(status)
                .withBody(status == 401 ? KEY_ERROR_BODY : "<html>error " + KEY + "</html>"));

        assertRunFullLeaksNoKey(output);
    }

    @Test
    @DisplayName("OPS-5-F1 1페이지 성공, 2페이지 500 → 감싼 예외에 page=2, 키 없음, 이미 커밋된 1페이지 진행(fetched=100)은 그대로")
    void f1_failureOnSecondPageKeepsFirstPageProgress() {
        okPage(1, 200, 1, 100);
        onPage(2, aResponse().withStatus(500).withBody("<html>error</html>"));

        Gov24ApiException e = runFullFails();

        assertThat(e.page()).isEqualTo(2);
        assertThat(e.httpStatus()).isEqualTo(500);
        assertThat(e.getMessage()).startsWith("HTTP_ERROR ").contains("status=500", "page=2");
        assertNoKey(e.getMessage());
        assertThat(syncRunQueryService.list(1)).extracting(SyncRunView::fetched).containsExactly(100);
    }

    @Test
    @DisplayName("OPS-5-A1 공공 API 실패 뒤 운영 API(목록·단건·실패 목록) 응답 어디에도 키가 없다")
    void a1_opsApiResponsesHaveNoKey() throws Exception {
        okPage(1, 200, 1, 100);
        onPage(2, aResponse().withStatus(401).withBody(KEY_ERROR_BODY));
        runFullFails();
        long runId = syncRunQueryService.list(1).get(0).runId();

        for (String uri : new String[] {
                "/ops/v1/sync-runs?size=20", "/ops/v1/sync-runs/" + runId, "/ops/v1/sync-runs/" + runId + "/failures"}) {
            var result = mvc.get().uri(uri).exchange();
            assertThat(result).as(uri).hasStatusOk();
            assertNoKey(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        }
    }
}
