package io.github.cheatiskey.subsidy.ingestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** SYNC-6-B4 재시도 설정이 앱 빈에 연결되는지 (통합, WireMock). 기본 4번이 아닌 2번으로 둔다. */
@TestPropertySource(properties = {
        "subsidy.gov24.connect-timeout=5s",
        "subsidy.gov24.read-timeout=5s",
        "subsidy.gov24.retry.max-attempts=2",
        "subsidy.gov24.retry.intervals=0s"
})
class SyncRetryConfiguredIntegrationTest extends Gov24RetrySyncSupport {

    @Test
    @DisplayName("SYNC-6-B4 max-attempts=2 → 2페이지 500이 계속되면 호출 2번, 실패 행 (LIST,2,500,HTTP_ERROR,2), PARTIAL")
    void b4_configuredMaxAttemptsReachesApp() {
        assertSecondPageGivesUp(status(500), 500, "HTTP_ERROR", 2);
    }
}
