package io.github.cheatiskey.subsidy.ingestion;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static io.github.cheatiskey.subsidy.ingestion.ServiceKeyForms.KEY;
import static io.github.cheatiskey.subsidy.ingestion.ServiceKeyForms.assertNoKey;
import static io.github.cheatiskey.subsidy.ingestion.ServiceKeyForms.stackTrace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.Slf4jNotifier;
import com.github.tomakehurst.wiremock.http.Fault;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * OPS-5 공공 API 호출 실패를 감싼 예외로 바꾸는지 (통합: Gov24Client + WireMock, 스프링 없이).
 * 서비스 키는 + / = 가 든 ab+c/d==. 타임아웃은 기본 5초, 타임아웃 시험(E5)만 0.3초.
 */
class Gov24ClientErrorTest {

    static final String LIST_PATH = "/api/gov24/v3/serviceList";
    static final String KEY_ERROR_BODY = Gov24Fixtures.load("error-invalid-key-401.json");
    /** HTTP/1.1만 (h2c 업그레이드 안 함, docs/decisions.md). 가짜 서버 알림 로그는 끔. */
    static final WireMockServer GOV24 = new WireMockServer(options().dynamicPort()
            .http2PlainDisabled(true).notifier(new Slf4jNotifier(false)));

    static {
        GOV24.start();
    }

    @AfterAll
    static void stop() {
        GOV24.stop();
    }

    @BeforeEach
    void reset() {
        GOV24.resetAll();
    }

    /** 정상·HTTP 오류 시험용: 타임아웃을 넉넉하게(5초) 둔다. */
    private static Gov24Client clientFor(String baseUrl) {
        return clientWithReadTimeout(baseUrl, Duration.ofSeconds(5));
    }

    private static Gov24Client clientWithReadTimeout(String baseUrl, Duration readTimeout) {
        return new Gov24Client(new Gov24Properties(baseUrl, KEY, 100, Duration.ofSeconds(5), readTimeout));
    }

    private static void respond(ResponseDefinitionBuilder response) {
        GOV24.stubFor(get(urlPathEqualTo(LIST_PATH)).willReturn(response));
    }

    /** fetchPage가 던진 예외가 감싼 예외인지 확인하고 꺼낸다. 메시지·스택 트레이스에 키가 없어야 한다. */
    private static Gov24ApiException fetchFails(Gov24Client client, int page) {
        Throwable thrown = catchThrowable(() -> client.fetchPage(page, 100));
        assertThat(thrown).isInstanceOf(Gov24ApiException.class);
        Gov24ApiException e = (Gov24ApiException) thrown;
        assertThat(e.getCause()).as("원래 예외는 cause로 넣지 않음").isNull();
        assertNoKey(e.getMessage());
        assertNoKey(stackTrace(e));
        return e;
    }

    @Test
    @DisplayName("OPS-5-N3 (회귀) 정상 호출에서는 키가 ab%2Bc%2Fd%3D%3D로 그대로 나가고, HTTP/1.1 고정이라 Upgrade 헤더가 없다")
    void n3_keyIsSentEncoded() {
        respond(aResponse().withStatus(200).withBody("{\"data\":[]}"));

        String body = clientFor(GOV24.baseUrl()).fetchPage(1, 100);

        assertThat(body).isEqualTo("{\"data\":[]}");
        // HTTP/1.1 고정: h2c 업그레이드를 시도하지 않는다 (docs/decisions.md)
        GOV24.verify(1, getRequestedFor(urlPathEqualTo(LIST_PATH)).withoutHeader("Upgrade"));
        GOV24.verify(1, getRequestedFor(urlMatching(LIST_PATH + "\\?.*serviceKey=ab%2Bc%2Fd%3D%3D(&.*)?")));
    }


    @ParameterizedTest(name = "[{index}] HTTP {0}")
    @ValueSource(ints = {400, 401, 403, 404, 429})
    @DisplayName("OPS-5-E3 4xx면 감싼 예외(HTTP_ERROR), 상태 보존, 공공 API code=-4만 남기고 본문 전체는 넣지 않는다")
    void e3_clientErrorKeepsStatusAndApiCodeOnly(int status) {
        respond(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(KEY_ERROR_BODY));

        Gov24ApiException e = fetchFails(clientFor(GOV24.baseUrl()), 1);

        assertThat(e.kind()).isEqualTo(Gov24ApiException.Kind.HTTP_ERROR);
        assertThat(e.httpStatus()).isEqualTo(status);
        assertThat(e.path()).isEqualTo(LIST_PATH);
        assertThat(e.page()).isEqualTo(1);
        assertThat(e.apiCode()).isEqualTo("-4");
        assertThat(e.getMessage()).startsWith("HTTP_ERROR ")
                .contains("status=" + status, "path=" + LIST_PATH, "page=1", "code=-4")
                .doesNotContain("등록되지 않은 인증키");
    }

    @ParameterizedTest(name = "[{index}] HTTP {0}")
    @ValueSource(ints = {500, 503})
    @DisplayName("OPS-5-E4 5xx(HTML 오류 페이지)면 감싼 예외(HTTP_ERROR), 상태 보존, 본문 없음")
    void e4_serverErrorHasNoBody(int status) {
        respond(aResponse().withStatus(status).withHeader("Content-Type", "text/html")
                .withBody("<html><body>Internal Error " + KEY + "</body></html>"));

        Gov24ApiException e = fetchFails(clientFor(GOV24.baseUrl()), 2);

        assertThat(e.kind()).isEqualTo(Gov24ApiException.Kind.HTTP_ERROR);
        assertThat(e.httpStatus()).isEqualTo(status);
        assertThat(e.page()).isEqualTo(2);
        assertThat(e.apiCode()).isNull();
        assertThat(e.getMessage()).startsWith("HTTP_ERROR ").contains("status=" + status, "page=2").doesNotContain("<html", "Internal Error");
    }

    @Test
    @DisplayName("OPS-5-E5 (짝 1) 응답 지연 1.5초, 응답 타임아웃 0.3초 → 감싼 예외(TIMEOUT), 경로·page 있음")
    void e5_shortReadTimeoutFails() {
        respond(aResponse().withStatus(200).withBody("{\"data\":[]}").withFixedDelay(1500));

        Gov24ApiException e = fetchFails(clientWithReadTimeout(GOV24.baseUrl(), Duration.ofMillis(300)), 4);

        assertThat(e.kind()).isEqualTo(Gov24ApiException.Kind.TIMEOUT);
        assertThat(e.path()).isEqualTo(LIST_PATH);
        assertThat(e.page()).isEqualTo(4);
        assertThat(e.getMessage()).startsWith("TIMEOUT ").contains("path=" + LIST_PATH, "page=4");
    }

    @Test
    @DisplayName("OPS-5-E5 (짝 2) 같은 지연 1.5초, 응답 타임아웃 5초 → 정상 응답 (설정이 실제로 적용되는지)")
    void e5_longReadTimeoutSucceeds() {
        respond(aResponse().withStatus(200).withBody("{\"data\":[]}").withFixedDelay(1500));

        String body = clientWithReadTimeout(GOV24.baseUrl(), Duration.ofSeconds(5)).fetchPage(4, 100);

        assertThat(body).isEqualTo("{\"data\":[]}");
    }

    @Test
    @DisplayName("OPS-5-E6 연결을 맺지 못함(127.0.0.1의 닫힌 포트) → 감싼 예외(CONNECTION), 경로·page 있음")
    void e6_connectionRefused() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        Gov24ApiException e = fetchFails(clientFor("http://127.0.0.1:" + closedPort), 5);

        assertThat(e.kind()).isEqualTo(Gov24ApiException.Kind.CONNECTION);
        assertThat(e.path()).isEqualTo(LIST_PATH);
        assertThat(e.page()).isEqualTo(5);
        assertThat(e.getMessage()).startsWith("CONNECTION ").contains("path=" + LIST_PATH, "page=5");
    }

    @Test
    @DisplayName("OPS-5-E6 연결을 맺은 뒤 끊김(connection reset) → 감싼 예외(IO), 경로·page 있음")
    void e6_connectionResetAfterConnect() {
        respond(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER));

        Gov24ApiException e = fetchFails(clientFor(GOV24.baseUrl()), 6);

        assertThat(e.kind()).isEqualTo(Gov24ApiException.Kind.IO);
        assertThat(e.path()).isEqualTo(LIST_PATH);
        assertThat(e.page()).isEqualTo(6);
        assertThat(e.getMessage()).startsWith("IO ").contains("path=" + LIST_PATH, "page=6");
    }

    @Test
    @DisplayName("OPS-5-E7 Content-Length(1000)보다 짧게 보내고 끊으면(HTTP/1.1) 본문 읽기 실패 → 감싼 예외(IO)")
    void e7_truncatedBody() throws IOException {
        try (TruncatedBodyServer server = new TruncatedBodyServer()) {
            Gov24ApiException e = fetchFails(clientFor(server.baseUrl()), 7);

            assertThat(e.kind()).isEqualTo(Gov24ApiException.Kind.IO);
            assertThat(e.page()).isEqualTo(7);
            assertThat(e.getMessage()).startsWith("IO ").contains("path=" + LIST_PATH, "page=7");
        }
    }
}
