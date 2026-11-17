package io.github.cheatiskey.subsidy.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** SYNC-6-B4 재시도 설정 `subsidy.gov24.retry.*` (단위). 실제로 쓰이는지는 ServiceListCollectorRetryTest. */
class Gov24RetryPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class);

    @Test
    @DisplayName("SYNC-6-B4 설정이 없으면 최대 4번, 간격 1s·4s·16s, 지터 비율 0.2")
    void b4_defaults() {
        runner.run(context -> {
            Gov24RetryProperties retry = context.getBean(Gov24RetryProperties.class);
            assertThat(retry.maxAttempts()).isEqualTo(4);
            assertThat(retry.intervals())
                    .containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(4), Duration.ofSeconds(16));
            assertThat(retry.jitterRatio()).isEqualTo(0.2);
        });
    }

    @Test
    @DisplayName("SYNC-6-B4 (짝) 설정한 값(횟수 2, 간격 0s, 지터 0)이 그대로 들어간다")
    void b4_configuredValues() {
        runner.withPropertyValues(
                        "subsidy.gov24.retry.max-attempts=2",
                        "subsidy.gov24.retry.intervals=0s",
                        "subsidy.gov24.retry.jitter-ratio=0")
              .run(context -> {
                  assertThat(context).hasNotFailed();
                  Gov24RetryProperties retry = context.getBean(Gov24RetryProperties.class);
                  assertThat(retry.maxAttempts()).isEqualTo(2);
                  assertThat(retry.intervals()).containsExactly(Duration.ZERO);
                  assertThat(retry.jitterRatio()).isEqualTo(0.0);
              });
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "subsidy.gov24.retry.max-attempts=0",
            "subsidy.gov24.retry.intervals=1s,-1s,16s",
            "subsidy.gov24.retry.intervals="
    })
    @DisplayName("SYNC-6-B4 횟수 0, 음수 간격, 빈 간격 목록이면 기동 실패")
    void b4_invalidValuesFailAtStartup(String property) {
        runner.withPropertyValues(property)
              .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(Gov24RetryProperties.class)
    static class PropertiesConfig {
    }
}
