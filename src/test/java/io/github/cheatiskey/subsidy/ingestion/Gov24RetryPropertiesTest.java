package io.github.cheatiskey.subsidy.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * SYNC-6-B4 재시도 설정 `subsidy.gov24.retry.*` (단위). 실제로 쓰이는지는 ServiceListCollectorRetryTest와
 * SyncRetryConfiguredIntegrationTest. 무작위 지연 비율은 설정이 아닌 상수 20%라 여기서 다루지 않는다.
 */
class Gov24RetryPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class);

    @Test
    @DisplayName("SYNC-6-B4 설정이 없으면 최대 4번, 간격 1s·4s·16s")
    void b4_defaults() {
        runner.run(context -> {
            Gov24RetryProperties retry = context.getBean(Gov24RetryProperties.class);
            assertThat(retry.maxAttempts()).isEqualTo(4);
            assertThat(retry.intervals())
                    .containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(4), Duration.ofSeconds(16));
        });
    }

    @Test
    @DisplayName("SYNC-6-B4 (짝) 설정한 값(횟수 2, 간격 0s)이 그대로 들어간다")
    void b4_configuredValues() {
        runner.withPropertyValues("subsidy.gov24.retry.max-attempts=2", "subsidy.gov24.retry.intervals=0s")
              .run(context -> {
                  assertThat(context).hasNotFailed();
                  Gov24RetryProperties retry = context.getBean(Gov24RetryProperties.class);
                  assertThat(retry.maxAttempts()).isEqualTo(2);
                  assertThat(retry.intervals()).containsExactly(Duration.ZERO);
              });
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
            "subsidy.gov24.retry.max-attempts=2;subsidy.gov24.retry.intervals=0s,5s,9s | 3",
            "subsidy.gov24.retry.max-attempts=1;subsidy.gov24.retry.intervals=1s        | 1"
    })
    @DisplayName("SYNC-6-B4 (짝) 간격이 (횟수−1)개 이상이면 정상 기동: 남는 간격(횟수 2·간격 3개), 재시도 없음(횟수 1·간격 1s)")
    void b4_enoughIntervalsStartUp(String properties, int intervalCount) {
        runner.withPropertyValues(properties.split(";"))
              .run(context -> {
                  assertThat(context).hasNotFailed();
                  assertThat(context.getBean(Gov24RetryProperties.class).intervals()).hasSize(intervalCount);
              });
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
            "subsidy.gov24.retry.max-attempts=0                                         | max-attempts",
            "subsidy.gov24.retry.max-attempts=-1                                        | max-attempts",
            "subsidy.gov24.retry.intervals=1s,-1s,16s                                   | intervals",
            "subsidy.gov24.retry.intervals=                                             | intervals",
            "subsidy.gov24.retry.max-attempts=4;subsidy.gov24.retry.intervals=1s,4s     | intervals"
    })
    @DisplayName("SYNC-6-B4 횟수 0·음수, 음수 간격, 빈 간격 목록, 간격이 (횟수−1)개 미만이면 기동 실패, 원인 메시지에 속성 이름")
    void b4_invalidValuesFailAtStartup(String properties, String propertyName) {
        runner.withPropertyValues(properties.split(";"))
              .run(context -> {
                  assertThat(context).hasFailed();
                  assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(propertyName);
              });
    }

    @Configuration
    @EnableConfigurationProperties(Gov24RetryProperties.class)
    static class PropertiesConfig {
    }
}
