package io.github.cheatiskey.subsidy.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SYNC-6-F13 실제 재시도 sleeper 빈(IngestionConfig.retrySleeper)의 중단 신호 처리 (단위). */
class RetrySleeperTest {

    private final Sleeper sleeper = new IngestionConfig().retrySleeper();

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted(); // 다음 테스트에 중단 표시가 남지 않게
    }

    @Test
    @DisplayName("SYNC-6-F13 중단 표시가 켜진 스레드에서 sleep(5s) → 기다리지 않고 바로 IllegalStateException, 중단 표시는 되살아남")
    void f13_interruptedSleepFailsFastAndKeepsInterruptFlag() {
        Thread.currentThread().interrupt();
        long start = System.nanoTime();

        Throwable thrown = catchThrowable(() -> sleeper.sleep(Duration.ofSeconds(5)));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        assertThat(Thread.interrupted()).as("중단 표시").isTrue();
    }

    @Test
    @DisplayName("SYNC-6-F13 (짝) 중단 표시가 없으면 10ms를 실제로 기다리고 예외 없음")
    void f13_sleepWaitsWithoutInterrupt() {
        long start = System.nanoTime();

        assertThatCode(() -> sleeper.sleep(Duration.ofMillis(10))).doesNotThrowAnyException();

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(10));
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }
}
