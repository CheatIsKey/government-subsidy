package io.github.cheatiskey.subsidy.ingestion;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/** 공공 API serviceList 호출. 본문을 가공하지 않고 문자열로 돌려준다. */
@Component
public class Gov24Client implements ServiceListSource {

    static final String LIST_PATH = "/api/gov24/v3/serviceList";

    private final Gov24Properties properties;
    private final RestClient restClient;

    public Gov24Client(Gov24Properties properties) {
        this.properties = properties;
        // HTTP/1.1 고정(기본 HTTP/2는 Upgrade: h2c를 보냄). 타임아웃을 설정하지 않으면 제한 없음.
        HttpClient.Builder http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1);
        if (properties.connectTimeout() != null) {
            http.connectTimeout(properties.connectTimeout());
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http.build());
        if (properties.readTimeout() != null) {
            factory.setReadTimeout(properties.readTimeout());
        }
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** 실패는 모두 Gov24ApiException으로 던진다. 로그는 남기지 않는다. */
    @Override
    public String fetchPage(int page, int perPage) {
        try {
            return restClient.get().uri(listUri(page, perPage)).retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        throw Gov24Errors.httpError(response.getStatusCode().value(), LIST_PATH, page, body,
                                properties.serviceKey());
                    })
                    .body(String.class);
        } catch (RestClientException e) {
            throw Gov24Errors.ioError(ioCause(e), LIST_PATH, page, properties.serviceKey());
        }
    }

    /** Spring이 감싼 예외 사슬에서 처음 나오는 IOException을 찾는다. 없으면 받은 예외 그대로. */
    private static Throwable ioCause(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof IOException) {
                return t;
            }
        }
        return e;
    }

    /**
     * 서비스 키는 +, /, = 까지 퍼센트 인코딩한다 (docs/decisions.md).
     * 이미 인코딩한 값을 넣으므로 build(true)로 다시 인코딩하지 않게 한다.
     */
    URI listUri(int page, int perPage) {
        return UriComponentsBuilder.fromUriString(properties.baseUrl())
                .path(LIST_PATH)
                .queryParam("page", page)
                .queryParam("perPage", perPage)
                .queryParam("serviceKey", UriUtils.encode(properties.serviceKey(), StandardCharsets.UTF_8))
                .build(true)
                .toUri();
    }
}
