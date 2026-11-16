package io.github.cheatiskey.subsidy.ingestion;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

/**
 * OPS-5-E11 타임아웃 경우 (통합). 응답 타임아웃 2초, 가짜 서버 지연 6초(제한의 3배, docs/decisions.md).
 * 짧은 타임아웃이 다른 시험에 섞이지 않도록 클래스를 따로 둔다.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
        "subsidy.gov24.connect-timeout=5s",
        "subsidy.gov24.read-timeout=2s"
})
class ServiceKeyTimeoutIntegrationTest extends Gov24StubbedSyncSupport {

    @Test
    @DisplayName("OPS-5-E11 동기화 중 타임아웃 → 콘솔 전체와 스택 트레이스에 키 없음, 세 로거 로그 0건")
    void e11_timeoutLeaksNoKey(CapturedOutput output) {
        onPage(1, aResponse().withStatus(200).withBody("{\"data\":[]}").withFixedDelay(6000));

        assertRunFullLeaksNoKey(output);
    }
}
