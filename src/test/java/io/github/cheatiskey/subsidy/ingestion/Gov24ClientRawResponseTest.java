package io.github.cheatiskey.subsidy.ingestion;

import static io.github.cheatiskey.subsidy.ingestion.ServiceKeyForms.KEY;
import static io.github.cheatiskey.subsidy.ingestion.ServiceKeyForms.UPPER_ENCODED;
import static io.github.cheatiskey.subsidy.ingestion.ServiceKeyForms.assertNoKey;
import static io.github.cheatiskey.subsidy.ingestion.ServiceKeyForms.stackTrace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

/**
 * OPS-5-E1 추가 (PR #8 CodeRabbit 지적 3·4): 잘린 오류 본문, 해석할 수 없는 Content-Type (통합: Gov24Client + 직접 만든 HTTP/1.1 서버).
 * WireMock은 헤더와 Content-Length를 고쳐 보낼 수 있어서 응답 바이트를 그대로 보내는 TruncatedBodyServer를 쓴다.
 * 메시지 형식 (docs/decisions.md): {@code <종류> status= path= page= code= cause=<전체 클래스명>}, 없는 칸은 생략.
 */
class Gov24ClientRawResponseTest {

    static final String LIST_PATH = "/api/gov24/v3/serviceList";
    static final String KEY_ERROR_BODY = Gov24Fixtures.load("error-invalid-key-401.json");
    static final String HTML_BODY = "<html><body>Service Unavailable " + KEY + "</body></html>";
    static final String NOT_A_TYPE = "not-a-type";
    static final String UNKNOWN_CHARSET = "text/html; charset=x-no-such-charset";
    /** 해석할 수 없는 Content-Type 값 안에 키(원래 값·대문자 인코딩)를 넣는다 (E17). */
    static final String BAD_TYPE_WITH_KEY = "bad type " + KEY + " " + UPPER_ENCODED;

    private static Gov24Client clientFor(TruncatedBodyServer server) {
        return new Gov24Client(new Gov24Properties(server.baseUrl(), KEY, 100, Duration.ofSeconds(5), Duration.ofSeconds(5)));
    }

    /** 본문을 다 보내는 응답. contentType이 null이면 Content-Type 헤더를 넣지 않는다. */
    private static String response(String statusLine, String contentType, String body) {
        return response(statusLine, contentType, body.getBytes(StandardCharsets.UTF_8).length, body);
    }

    /** contentLength를 본문보다 크게 주면 "알린 길이보다 짧은 본문 뒤 연결 끊김"이 된다. */
    private static String response(String statusLine, String contentType, int contentLength, String body) {
        return "HTTP/1.1 " + statusLine + "\r\n"
                + (contentType == null ? "" : "Content-Type: " + contentType + "\r\n")
                + "Content-Length: " + contentLength + "\r\n"
                + "Connection: close\r\n"
                + "\r\n"
                + body;
    }

    /** 응답 하나를 보내는 서버를 띄워 fetchPage를 부르고, 던져진 예외를 돌려준다. */
    private static Throwable fetchThrown(String rawResponse, int page) throws IOException {
        try (TruncatedBodyServer server = new TruncatedBodyServer(rawResponse)) {
            return catchThrowable(() -> clientFor(server).fetchPage(page, 100));
        }
    }

    private static String fetchBody(String rawResponse) throws IOException {
        try (TruncatedBodyServer server = new TruncatedBodyServer(rawResponse)) {
            return clientFor(server).fetchPage(1, 100);
        }
    }

    /** Spring 예외가 아니라 감싼 예외여야 하고, cause가 없고, 메시지·스택 트레이스 전체에 키가 없어야 한다. */
    private static Gov24ApiException fetchFails(String rawResponse, int page) throws IOException {
        Throwable thrown = fetchThrown(rawResponse, page);
        assertThat(thrown).isExactlyInstanceOf(Gov24ApiException.class);
        Gov24ApiException e = (Gov24ApiException) thrown;
        assertThat(e.getCause()).as("원래 예외는 cause로 넣지 않음").isNull();
        assertNoKey(e.getMessage());
        assertNoKey(stackTrace(e));
        return e;
    }

    /** 전제 확인: 이 값은 Spring이 실제로 해석하지 못하는 Content-Type이다. */
    private static void assertUnparseable(String contentType) {
        assertThatThrownBy(() -> MediaType.parseMediaType(contentType)).isInstanceOf(InvalidMediaTypeException.class);
    }

    /** 메시지의 cause= 뒤 클래스가 IOException 계열인지. 정확한 하위 타입은 JDK HttpClient 내부에 달려 있어 고정하지 않는다. */
    private static void assertIoCauseName(String message) throws ClassNotFoundException {
        String causeName = message.substring(message.indexOf(" cause=") + " cause=".length());
        assertThat(IOException.class).as("cause=" + causeName).isAssignableFrom(Class.forName(causeName));
    }

    @Test
    @DisplayName("OPS-5-E12 503에서 알린 길이보다 짧은 본문 뒤 연결이 끊기면 감싼 예외(IO), status=503 보존, cause는 IOException 계열")
    void e12_truncatedServerErrorBodyKeepsStatus() throws Exception {
        Gov24ApiException e = fetchFails(response("503 Service Unavailable", "text/html", 1000, "<html><body>Service Unav"), 12);

        assertThat(e.getMessage()).matches("IO status=503 path=/api/gov24/v3/serviceList page=12 cause=\\S+");
        assertIoCauseName(e.getMessage());
        assertThat(e.kind()).isEqualTo(Gov24ApiException.Kind.IO);
        assertThat(e.httpStatus()).as("httpStatus").isEqualTo(503);
        assertThat(e.apiCode()).isNull();
    }

    @Test
    @DisplayName("OPS-5-E13 401에서 {\"code\":-4 다음에 연결이 끊기면 감싼 예외(IO), status=401 보존, 잘린 JSON에서 code를 꺼내지 않음")
    void e13_truncatedClientErrorBodyHasNoCode() throws Exception {
        Gov24ApiException e = fetchFails(response("401 Unauthorized", "application/json", 1000, "{\"code\":-4"), 13);

        assertThat(e.getMessage()).matches("IO status=401 path=/api/gov24/v3/serviceList page=13 cause=\\S+");
        assertIoCauseName(e.getMessage());
        assertThat(e.kind()).isEqualTo(Gov24ApiException.Kind.IO);
        assertThat(e.httpStatus()).as("httpStatus").isEqualTo(401);
        assertThat(e.apiCode()).isNull();
    }

    @Test
    @DisplayName("OPS-5-E7R (회귀) 200 잘린 본문(OPS-5-E7과 같은 응답)은 지금처럼 status 칸 없이 IO, httpStatus는 null")
    void e7r_truncatedOkBodyHasNoStatus() throws Exception {
        Gov24ApiException e;
        try (TruncatedBodyServer server = new TruncatedBodyServer()) {
            Throwable thrown = catchThrowable(() -> clientFor(server).fetchPage(7, 100));
            assertThat(thrown).isExactlyInstanceOf(Gov24ApiException.class);
            e = (Gov24ApiException) thrown;
        }

        assertThat(e.getMessage()).matches("IO path=/api/gov24/v3/serviceList page=7 cause=\\S+");
        assertIoCauseName(e.getMessage());
        assertThat(e.kind()).isEqualTo(Gov24ApiException.Kind.IO);
        assertThat(e.httpStatus()).as("httpStatus").isNull();
        assertThat(e.getCause()).isNull();
        assertNoKey(stackTrace(e));
    }

    @Test
    @DisplayName("OPS-5-E14401에 Content-Type: not-a-type이어도 HTTP_ERROR status=401 code=-4, Spring 예외가 밖으로 나가지 않음")
    void e14_clientErrorWithInvalidContentType() throws Exception {
        assertUnparseable(NOT_A_TYPE);

        Gov24ApiException e = fetchFails(response("401 Unauthorized", NOT_A_TYPE, KEY_ERROR_BODY), 14);

        assertThat(e.kind()).isEqualTo(Gov24ApiException.Kind.HTTP_ERROR);
        assertThat(e.httpStatus()).isEqualTo(401);
        assertThat(e.apiCode()).isEqualTo("-4");
        assertThat(e.getMessage()).isEqualTo("HTTP_ERROR status=401 path=/api/gov24/v3/serviceList page=14 code=-4");
    }

    @Test
    @DisplayName("OPS-5-E15 503에 없는 charset의 Content-Type, HTML 본문이면 HTTP_ERROR status=503, 본문 없음")
    void e15_serverErrorWithUnknownCharset() throws Exception {
        assertUnparseable(UNKNOWN_CHARSET);

        Gov24ApiException e = fetchFails(response("503 Service Unavailable", UNKNOWN_CHARSET, HTML_BODY), 15);

        assertThat(e.kind()).isEqualTo(Gov24ApiException.Kind.HTTP_ERROR);
        assertThat(e.httpStatus()).isEqualTo(503);
        assertThat(e.apiCode()).isNull();
        assertThat(e.getMessage()).isEqualTo("HTTP_ERROR status=503 path=/api/gov24/v3/serviceList page=15");
    }

    @Test
    @DisplayName("OPS-5-E16 200에 Content-Type: not-a-type이면 성공으로 보지 않고 감싼 예외(IO), status=200, cause=InvalidMediaTypeException")
    void e16_okWithInvalidContentTypeFails() throws Exception {
        assertUnparseable(NOT_A_TYPE);

        Gov24ApiException e = fetchFails(response("200 OK", NOT_A_TYPE, "{\"data\":[]}"), 16);

        assertThat(e.kind()).isEqualTo(Gov24ApiException.Kind.IO);
        assertThat(e.httpStatus()).isEqualTo(200);
        assertThat(e.apiCode()).isNull();
        assertThat(e.getMessage()).isEqualTo(
                "IO status=200 path=/api/gov24/v3/serviceList page=16 cause=org.springframework.http.InvalidMediaTypeException");
    }

    static Stream<Arguments> keyInContentType() {
        return Stream.of(
                Arguments.of("401 Unauthorized", KEY_ERROR_BODY, "HTTP_ERROR status=401 path=/api/gov24/v3/serviceList page=17 code=-4"),
                Arguments.of("503 Service Unavailable", HTML_BODY, "HTTP_ERROR status=503 path=/api/gov24/v3/serviceList page=17"),
                Arguments.of("200 OK", "{\"data\":[]}",
                        "IO status=200 path=/api/gov24/v3/serviceList page=17 cause=org.springframework.http.InvalidMediaTypeException"));
    }

    @ParameterizedTest(name = "[{index}] HTTP {0}")
    @MethodSource("keyInContentType")
    @DisplayName("OPS-5-E17 해석할 수 없는 Content-Type 값에 키가 있어도 메시지·스택 트레이스 전체·cause 사슬에 키 없음")
    void e17_keyInInvalidContentTypeIsNotExposed(String statusLine, String body, String expectedMessage) throws Exception {
        // 전제: Spring 해석 예외의 메시지에는 헤더 값 전체(키 포함)가 들어간다
        assertThatThrownBy(() -> MediaType.parseMediaType(BAD_TYPE_WITH_KEY))
                .isInstanceOf(InvalidMediaTypeException.class).hasMessageContaining(KEY);

        Gov24ApiException e = fetchFails(response(statusLine, BAD_TYPE_WITH_KEY, body), 17);

        assertThat(e.getMessage()).isEqualTo(expectedMessage);
    }

    @Test
    @DisplayName("OPS-5-E19 (회귀) 401에 Content-Type 헤더가 없고 본문이 {\"code\":-4}이면 지금처럼 HTTP_ERROR status=401 code=-4")
    void e19_clientErrorWithoutContentType() throws Exception {
        Gov24ApiException e = fetchFails(response("401 Unauthorized", null, "{\"code\":-4}"), 19);

        assertThat(e.kind()).isEqualTo(Gov24ApiException.Kind.HTTP_ERROR);
        assertThat(e.httpStatus()).isEqualTo(401);
        assertThat(e.apiCode()).isEqualTo("-4");
        assertThat(e.getMessage()).isEqualTo("HTTP_ERROR status=401 path=/api/gov24/v3/serviceList page=19 code=-4");
    }

    @Test
    @DisplayName("OPS-5-B4 500에 빈 본문(Content-Length 0)이면 HTTP_ERROR status=500, code 칸 없음")
    void b4_serverErrorWithEmptyBody() throws Exception {
        Gov24ApiException e = fetchFails(response("500 Internal Server Error", "text/html", ""), 4);

        assertThat(e.kind()).isEqualTo(Gov24ApiException.Kind.HTTP_ERROR);
        assertThat(e.httpStatus()).isEqualTo(500);
        assertThat(e.apiCode()).isNull();
        assertThat(e.getMessage()).isEqualTo("HTTP_ERROR status=500 path=/api/gov24/v3/serviceList page=4");
    }

    @Test
    @DisplayName("OPS-5-B5 (회귀) 200에 Content-Type 값이 빈 칸이고 정상 JSON이면 지금처럼 오류 없이 본문을 돌려준다")
    void b5_okWithBlankContentType() throws Exception {
        String json = "{\"data\":[],\"page\":1}";

        assertThat(fetchBody(response("200 OK", "", json))).isEqualTo(json);
    }

    @Test
    @DisplayName("OPS-5-N4 (회귀) 200, application/json, 한글이 든 정상 JSON이면 받은 본문과 정확히 같은 문자열")
    void n4_okKoreanJsonIsReturnedAsIs() throws Exception {
        String json = Gov24Fixtures.page(1, 1, 1, Gov24Fixtures.items(1, 1));
        assertThat(json).as("전제: 본문에 한글이 있음").contains("서비스 1");

        assertThat(fetchBody(response("200 OK", "application/json", json))).isEqualTo(json);
    }

    @Test
    @DisplayName("OPS-5-N5 (회귀) 200 빈 본문(Content-Length 0)이면 지금처럼 null (\"\" 아님)")
    void n5_okEmptyBodyIsNull() throws Exception {
        assertThat(fetchBody(response("200 OK", "application/json", ""))).isNull();
    }

    @Test
    @DisplayName("OPS-5-N5 (회귀) 204면 지금처럼 null (\"\" 아님)")
    void n5_noContentIsNull() throws Exception {
        assertThat(fetchBody("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n")).isNull();
    }
}
