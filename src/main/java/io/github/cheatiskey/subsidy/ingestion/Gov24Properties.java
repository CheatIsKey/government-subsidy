package io.github.cheatiskey.subsidy.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 껍데기(2단계 테스트 컴파일용). 로직 없음. 3단계에서 구현한다.
@ConfigurationProperties("subsidy.gov24")
public record Gov24Properties(String baseUrl, String serviceKey, int perPage) {
}
