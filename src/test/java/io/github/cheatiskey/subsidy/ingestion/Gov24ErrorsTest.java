package io.github.cheatiskey.subsidy.ingestion;

import static io.github.cheatiskey.subsidy.ingestion.ServiceKeyForms.ALL;
import static io.github.cheatiskey.subsidy.ingestion.ServiceKeyForms.KEY;
import static io.github.cheatiskey.subsidy.ingestion.ServiceKeyForms.LOWER_ENCODED;
import static io.github.cheatiskey.subsidy.ingestion.ServiceKeyForms.PARTIAL_ENCODED;
import static io.github.cheatiskey.subsidy.ingestion.ServiceKeyForms.UPPER_ENCODED;
import static io.github.cheatiskey.subsidy.ingestion.ServiceKeyForms.assertNoKey;
import static io.github.cheatiskey.subsidy.ingestion.ServiceKeyForms.stackTrace;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.InvalidMediaTypeException;

/**
 * OPS-5-E9, E10, E18, E20, B1, B2 서비스 키 가리기와 감싼 예외 (단위). 키는 + / = 가 든 ab+c/d==.
 * 메시지 예 (docs/decisions.md): HTTP_ERROR status=401 path=/api/gov24/v3/serviceList page=3 code=-4.
 * 앞서 만든 테스트(E9, E10, B2 등)는 칸 순서에 묶이지 않게 조각마다 contains로 본다.
 * E18·E20은 docs/cases.md OPS-5-E1 추가 절에서 형식(칸 순서, 없는 칸 생략)이 확정되어 메시지 전체를 isEqualTo로 비교한다.
 */
class Gov24ErrorsTest {

    private static final String PATH = "/api/gov24/v3/serviceList";
    /** 원인 예외 메시지에만 넣는 고유 문구. 감싼 예외 메시지에 나오면 원인 메시지를 넣은 것이다. */
    private static final String CAUSE_MARKER = "원인문구-7f3a";

    static Stream<String> keyForms() {
        return ALL.stream();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("keyForms")
    @DisplayName("OPS-5-E9 4xx 본문의 code에 키가 들어 있으면 꺼낸 code에서 ****로 가려진다")
    void e9_keyInResponseCodeIsMasked(String form) {
        String body = "{\"code\":\"E " + form + "\",\"msg\":\"요청 " + form + " 거부\"}";

        Gov24ApiException e = Gov24Errors.httpError(401, PATH, 3, body, KEY);

        assertThat(e.apiCode()).isEqualTo("E ****");
        assertThat(e.getMessage()).startsWith("HTTP_ERROR ").contains("code=E ****").doesNotContain("거부");
        assertNoKey(e.getMessage());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("keyForms")
    @DisplayName("OPS-5-E9 원인 예외 메시지는 가려서도 넣지 않는다 (클래스 이름만)")
    void e9_causeMessageIsNotIncluded(String form) {
        IOException cause = new IOException(CAUSE_MARKER + " GET http://gov24.test" + PATH + "?serviceKey=" + form);

        Gov24ApiException e = Gov24Errors.ioError(cause, PATH, 2, KEY);

        assertThat(e.kind()).isEqualTo(Gov24ApiException.Kind.IO);
        assertThat(e.page()).isEqualTo(2);
        assertThat(e.getMessage()).startsWith("IO ").contains("cause=java.io.IOException").doesNotContain(CAUSE_MARKER, "****");
        assertNoKey(e.getMessage());
    }

    @Test
    @DisplayName("OPS-5-E9 원인 메시지가 null이어도(new ConnectException()) 오류 없이 CONNECTION")
    void e9_nullCauseMessage() {
        Gov24ApiException e = Gov24Errors.ioError(new ConnectException(), PATH, 1, KEY);

        assertThat(e.kind()).isEqualTo(Gov24ApiException.Kind.CONNECTION);
        assertThat(e.page()).isEqualTo(1);
        assertThat(e.getMessage()).startsWith("CONNECTION ").contains("path=" + PATH, "page=1", "cause=java.net.ConnectException");
    }

    // 종류는 타입 규칙으로 나눈다. HttpConnectTimeoutException은 HttpTimeoutException의 자식이라
    // 좁은 타입부터 검사해야 한다 (docs/decisions.md). 아래 두 테스트가 짝이다.

    @Test
    @DisplayName("OPS-5-E6 (짝) 연결 대기 시간 초과(HttpConnectTimeoutException)는 CONNECTION — TIMEOUT으로 분류하면 안 됨")
    void e6_connectTimeoutIsConnection() {
        Gov24ApiException e = Gov24Errors.ioError(new HttpConnectTimeoutException("x"), PATH, 1, KEY);

        assertThat(e.kind()).isEqualTo(Gov24ApiException.Kind.CONNECTION);
        assertThat(e.getMessage()).startsWith("CONNECTION ")
                .contains("path=" + PATH, "page=1", "cause=java.net.http.HttpConnectTimeoutException");
    }

    @Test
    @DisplayName("OPS-5-E5 (짝) 응답 대기 시간 초과(HttpTimeoutException)는 TIMEOUT")
    void e5_responseTimeoutIsTimeout() {
        Gov24ApiException e = Gov24Errors.ioError(new HttpTimeoutException("x"), PATH, 1, KEY);

        assertThat(e.kind()).isEqualTo(Gov24ApiException.Kind.TIMEOUT);
        assertThat(e.getMessage()).startsWith("TIMEOUT ")
                .contains("path=" + PATH, "page=1", "cause=java.net.http.HttpTimeoutException");
    }

    @Test
    @DisplayName("OPS-5-E10 감싼 예외의 스택 트레이스 전체(Caused by 포함)에 키가 없다. 원래 예외는 cause로 넣지 않고 클래스 이름만 남긴다")
    void e10_stackTraceHasNoKey() {
        IOException cause = new IOException("serviceKey=" + UPPER_ENCODED,
                new IllegalStateException("inner " + KEY + " " + LOWER_ENCODED + " " + PARTIAL_ENCODED));

        Gov24ApiException e = Gov24Errors.ioError(cause, PATH, 2, KEY);

        assertThat(e.getCause()).isNull();
        assertThat(e.getMessage()).startsWith("IO ").contains("cause=java.io.IOException");
        assertThat(stackTrace(e)).doesNotContain("Caused by");
        assertNoKey(stackTrace(e));
    }

    static Stream<Arguments> causesWithKey() {
        return Stream.of(
                Arguments.of(new IOException(CAUSE_MARKER + " serviceKey=" + KEY + " " + UPPER_ENCODED), 503),
                Arguments.of(new InvalidMediaTypeException("bad " + KEY, CAUSE_MARKER + " " + UPPER_ENCODED), 200));
    }

    @ParameterizedTest(name = "[{index}] status {1}")
    @MethodSource("causesWithKey")
    @DisplayName("OPS-5-E18 원인 예외(IOException, InvalidMediaTypeException) 메시지에 키가 있어도 상태를 붙인 IO 메시지에는 원인 클래스 이름만, cause는 null")
    void e18_causeMessageWithKeyIsNotIncludedWithStatus(Throwable cause, int status) {
        Gov24ApiException e = Gov24Errors.ioErrorAfterStatus(cause, status, PATH, 18, KEY);

        assertThat(e.kind()).isEqualTo(Gov24ApiException.Kind.IO);
        assertThat(e.httpStatus()).as("httpStatus").isEqualTo(status);
        assertThat(e.page()).isEqualTo(18);
        assertThat(e.getCause()).isNull();
        assertThat(e.getMessage()).isEqualTo("IO status=" + status + " path=" + PATH + " page=18 cause=" + cause.getClass().getName());
        assertNoKey(stackTrace(e));
    }

    // E20: 상태를 받은 뒤 실패도 종류는 기존 타입 규칙(좁은 타입부터)을 따른다. 아래 두 테스트가 짝이다.

    @Test
    @DisplayName("OPS-5-E20 상태를 받은 뒤 오류 본문을 읽다가 응답 타임아웃(HttpTimeoutException)이면 TIMEOUT, status=503 보존, 키 없음")
    void e20_readTimeoutAfterStatusIsTimeout() {
        HttpTimeoutException cause = new HttpTimeoutException(CAUSE_MARKER + " serviceKey=" + KEY + " " + UPPER_ENCODED);

        Gov24ApiException e = Gov24Errors.ioErrorAfterStatus(cause, 503, PATH, 20, KEY);

        assertThat(e.kind()).isEqualTo(Gov24ApiException.Kind.TIMEOUT);
        assertThat(e.httpStatus()).as("httpStatus").isEqualTo(503);
        assertThat(e.getCause()).isNull();
        assertThat(e.getMessage()).isEqualTo("TIMEOUT status=503 path=" + PATH + " page=20 cause=java.net.http.HttpTimeoutException");
        assertNoKey(stackTrace(e));
    }

    @Test
    @DisplayName("OPS-5-E20 (짝) 상태를 받은 뒤라도 원인이 HttpConnectTimeoutException이면 기존 규칙대로 CONNECTION — TIMEOUT으로 분류하면 안 됨")
    void e20_connectTimeoutAfterStatusFollowsExistingRule() {
        Gov24ApiException e = Gov24Errors.ioErrorAfterStatus(new HttpConnectTimeoutException("x " + KEY), 503, PATH, 20, KEY);

        assertThat(e.kind()).isEqualTo(Gov24ApiException.Kind.CONNECTION);
        assertThat(e.httpStatus()).as("httpStatus").isEqualTo(503);
        assertThat(e.getCause()).isNull();
        assertThat(e.getMessage()).isEqualTo("CONNECTION status=503 path=" + PATH + " page=20 cause=java.net.http.HttpConnectTimeoutException");
        assertNoKey(stackTrace(e));
    }

    @Test
    @DisplayName("OPS-5-B1 키에 + / = 가 있어도 네 형태가 모두 ****로 가려진다")
    void b1_allFourFormsAreMasked() {
        String text = "a=" + KEY + " b=" + UPPER_ENCODED + " c=" + LOWER_ENCODED + " d=" + PARTIAL_ENCODED;

        assertThat(ServiceKeyMasker.mask(text, KEY)).isEqualTo("a=**** b=**** c=**** d=****");
    }

    @Test
    @DisplayName("OPS-5-B2 키가 빈 문자열이면 가리기가 오류 없이 원문을 그대로 둔다 (글자 사이에 **** 없음)")
    void b2_emptyKeyLeavesTextUnchanged() {
        assertThat(ServiceKeyMasker.mask("code=-4 page=3", "")).isEqualTo("code=-4 page=3");
    }

    @Test
    @DisplayName("OPS-5-B2 키가 빈 문자열인데 401이면 상태·경로·page는 정상이고 메시지에 ****가 끼지 않는다")
    void b2_emptyKeyHttpError() {
        Gov24ApiException e = Gov24Errors.httpError(401, PATH, 3, "{\"code\":-4,\"msg\":\"등록되지 않은 인증키 입니다.\"}", "");

        assertThat(e.kind()).isEqualTo(Gov24ApiException.Kind.HTTP_ERROR);
        assertThat(e.httpStatus()).isEqualTo(401);
        assertThat(e.path()).isEqualTo(PATH);
        assertThat(e.page()).isEqualTo(3);
        assertThat(e.apiCode()).isEqualTo("-4");
        assertThat(e.getMessage()).startsWith("HTTP_ERROR ").contains("status=401", "path=" + PATH, "page=3", "code=-4").doesNotContain("****");
    }

    @Test
    @DisplayName("OPS-5-B2 키가 빈 문자열일 때 ioError도 오류 없이 경로·page를 남기고 ****가 끼지 않는다")
    void b2_emptyKeyIoError() {
        Gov24ApiException e = Gov24Errors.ioError(new IOException("read failed"), PATH, 1, "");

        assertThat(e.kind()).isEqualTo(Gov24ApiException.Kind.IO);
        assertThat(e.page()).isEqualTo(1);
        assertThat(e.getMessage()).startsWith("IO ").contains("path=" + PATH, "page=1", "cause=java.io.IOException").doesNotContain("****");
    }
}
