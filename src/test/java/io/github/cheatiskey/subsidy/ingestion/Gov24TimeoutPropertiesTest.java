package io.github.cheatiskey.subsidy.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** SYNC-6-B5 공공 API 타임아웃 기본값 (단위). 설정이 비면 연결 5초·응답 30초, 무한 대기 없음. */
class Gov24TimeoutPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class)
            .withPropertyValues(
                    "subsidy.gov24.base-url=http://localhost:1",
                    "subsidy.gov24.service-key=test-service-key");

    @Test
    @DisplayName("SYNC-6-B5 타임아웃 설정이 없으면 연결 5초, 응답 30초")
    void b5_defaultTimeouts() {
        runner.run(context -> {
            Gov24Properties properties = context.getBean(Gov24Properties.class);
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    @Test
    @DisplayName("SYNC-6-B5 (짝) 타임아웃을 설정하면(연결 2초, 응답 7초) 그 값")
    void b5_configuredTimeouts() {
        runner.withPropertyValues(
                        "subsidy.gov24.connect-timeout=2s",
                        "subsidy.gov24.read-timeout=7s")
              .run(context -> {
                  Gov24Properties properties = context.getBean(Gov24Properties.class);
                  assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
                  assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(7));
              });
    }

    @Configuration
    @EnableConfigurationProperties(Gov24Properties.class)
    static class PropertiesConfig {
    }
}
