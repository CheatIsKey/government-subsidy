package io.github.cheatiskey.subsidy.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 요청을 한 번 받아 정해 둔 응답 바이트를 그대로 보내고 연결을 끊는 HTTP/1.1 서버.
 * 기본 생성자는 200과 Content-Length: 1000을 알린 뒤 본문을 몇 글자만 보낸다 (OPS-5-E7).
 * WireMock은 Content-Length를 본문 길이로 다시 맞춰 보내서 "잘린 본문"을 만들 수 없기 때문에 직접 만든다.
 * 응답 전체를 받는 생성자는 잘못된 Content-Type처럼 헤더를 그대로 보내야 하는 시험용이다 (OPS-5-E12~E19, B4, B5, N4, N5).
 */
final class TruncatedBodyServer implements AutoCloseable {

    static final String PARTIAL_BODY = "{\"data\":[";

    private static final String DEFAULT_RESPONSE = "HTTP/1.1 200 OK\r\n"
            + "Content-Type: application/json\r\n"
            + "Content-Length: 1000\r\n"
            + "Connection: close\r\n"
            + "\r\n"
            + PARTIAL_BODY;

    private final byte[] response;
    private final ServerSocket server;
    private final Thread worker;

    TruncatedBodyServer() throws IOException {
        this(DEFAULT_RESPONSE);
    }

    /** rawResponse(상태 줄, 헤더, 빈 줄, 본문)를 UTF-8 바이트로 바꿔 고치지 않고 그대로 보낸다. */
    TruncatedBodyServer(String rawResponse) throws IOException {
        response = rawResponse.getBytes(StandardCharsets.UTF_8);
        server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(10_000); // 요청이 안 오면 10초 뒤 스스로 끝낸다
        worker = new Thread(this::serveOnce, "truncated-body-server");
        worker.setDaemon(true);
        worker.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    private void serveOnce() {
        try (Socket socket = server.accept()) {
            readRequestHeaders(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            out.write(response);
            out.flush();
        } catch (IOException e) {
            // 서버를 닫으면서 accept가 끝난 경우. 테스트 쪽 단언이 결과를 판단한다.
        }
    }

    private static void readRequestHeaders(InputStream in) throws IOException {
        int matched = 0;
        byte[] end = {'\r', '\n', '\r', '\n'};
        int b;
        while (matched < end.length && (b = in.read()) != -1) {
            matched = (b == end[matched]) ? matched + 1 : (b == '\r' ? 1 : 0);
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
