package io.github.cheatiskey.subsidy.ingestion;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 공공 API(Gov24) 설정. perPage는 기본 1000, 2000을 넘으면 기동 실패 (SYNC-1-B4).
 * 실측: 3000 이상은 data 없이 "정상" 응답이 온다.
 */
@ConfigurationProperties("subsidy.gov24")
public record Gov24Properties(
        String baseUrl,
        String serviceKey,
        @DefaultValue("1000") int perPage,
        // 연결·응답 타임아웃. 비어 있으면 연결 5초, 응답 30초 (SYNC-6-B5).
        Duration connectTimeout,
        Duration readTimeout) {

    static final int MAX_PER_PAGE = 2000;
    static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);

    public Gov24Properties {
        if (perPage > MAX_PER_PAGE) {
            throw new IllegalArgumentException(
                    "subsidy.gov24.per-page(perPage)는 " + MAX_PER_PAGE + " 이하여야 합니다: " + perPage);
        }
        if (connectTimeout == null) {
            connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        }
        if (readTimeout == null) {
            readTimeout = DEFAULT_READ_TIMEOUT;
        }
    }

    /** 서비스 키가 로그에 새지 않도록 값을 가린다 (OPS-5). */
    @Override
    public String toString() {
        return "Gov24Properties[baseUrl=" + baseUrl + ", serviceKey=****, perPage=" + perPage + "]";
    }
}
