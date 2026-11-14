package io.github.cheatiskey.subsidy.ingestion;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/** 공공 API serviceList 호출. 본문을 가공하지 않고 문자열로 돌려준다. */
@Component
public class Gov24Client implements ServiceListSource {

    static final String LIST_PATH = "/api/gov24/v3/serviceList";

    private final Gov24Properties properties;
    private final RestClient restClient = RestClient.create();

    public Gov24Client(Gov24Properties properties) {
        this.properties = properties;
    }

    @Override
    public String fetchPage(int page, int perPage) {
        return restClient.get().uri(listUri(page, perPage)).retrieve().body(String.class);
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
