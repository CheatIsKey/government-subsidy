package io.github.cheatiskey.subsidy.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** SYNC-1-B4 perPage 설정값 (단위). 실측: 3000 이상은 data 없이 "정상" 응답이 온다. */
class Gov24PropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class)
            .withPropertyValues(
                    "subsidy.gov24.base-url=http://localhost:1",
                    "subsidy.gov24.service-key=test-service-key");

    @Test
    @DisplayName("SYNC-1-B4 perPage를 설정하지 않으면 1000")
    void b4_defaultPerPage() {
        runner.run(context -> assertThat(context.getBean(Gov24Properties.class).perPage()).isEqualTo(1000));
    }

    @Test
    @DisplayName("SYNC-1-B4 perPage 2000은 허용")
    void b4_perPage2000IsAllowed() {
        runner.withPropertyValues("subsidy.gov24.per-page=2000")
              .run(context -> {
                  assertThat(context).hasNotFailed();
                  assertThat(context.getBean(Gov24Properties.class).perPage()).isEqualTo(2000);
              });
    }

    @Test
    @DisplayName("SYNC-1-B4 perPage가 2000을 넘으면(2001) 기동할 때 perPage 설정 오류")
    void b4_perPageOver2000FailsAtStartup() {
        runner.withPropertyValues("subsidy.gov24.per-page=2001")
              .run(context -> {
                  assertThat(context).hasFailed();
                  assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("perPage");
              });
    }

    @Configuration
    @EnableConfigurationProperties(Gov24Properties.class)
    static class PropertiesConfig {
    }
}
