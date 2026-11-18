package io.github.cheatiskey.subsidy.ingestion;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * SYNC-6-F4 응답 타임아웃을 재시도 (통합, WireMock). 응답 타임아웃 2초, 가짜 서버 지연 6초(제한의 3배, docs/decisions.md).
 * 짧은 타임아웃이 다른 시험에 섞이지 않도록 클래스를 따로 둔다. 재시도 최대 4번·간격 0s.
 */
@TestPropertySource(properties = {
        "subsidy.gov24.connect-timeout=5s",
        "subsidy.gov24.read-timeout=2s",
        "subsidy.gov24.retry.max-attempts=4",
        "subsidy.gov24.retry.intervals=0s,0s,0s"
})
class SyncRetryTimeoutIntegrationTest extends Gov24RetrySyncSupport {

    @Test
    @DisplayName("SYNC-6-F4 2페이지 응답 타임아웃 4번 연속 → F3과 같음(호출 4번, PARTIAL), http_status 없음, error_code=TIMEOUT")
    void f4_readTimeoutFourTimesIsRecorded() {
        assertSecondPageGivesUp(aResponse().withStatus(200).withBody("{\"data\":[]}").withFixedDelay(6000),
                null, "TIMEOUT", 4);
    }
}
