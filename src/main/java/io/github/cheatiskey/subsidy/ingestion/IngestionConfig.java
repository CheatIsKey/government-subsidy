package io.github.cheatiskey.subsidy.ingestion;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({Gov24Properties.class, Gov24RetryProperties.class})
class IngestionConfig {

    /**
     * 재시도 사이에 실제로 기다린다 (SYNC-6). 기다리다 끊기면 중단 표시를 되살리고 멈춘다.
     * 간격이 0초여도 중단 표시를 먼저 확인한다 (SYNC-6-F13).
     */
    @Bean
    Sleeper retrySleeper() {
        return duration -> {
            if (Thread.currentThread().isInterrupted()) { // 간격이 0초여도 종료 신호를 먼저 확인 (SYNC-6-F13). 중단 표시는 지우지 않는다
                throw new IllegalStateException("재시도 대기 중 중단됨");
            }
            try {
                TimeUnit.NANOSECONDS.sleep(duration.toNanos());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("재시도 대기 중 중단됨");
            }
        };
    }

    /** 무작위 지연용 [0, 1) 난수 (SYNC-6-B3). */
    @Bean
    DoubleSupplier retryJitterRandom() {
        return () -> ThreadLocalRandom.current().nextDouble();
    }
}
