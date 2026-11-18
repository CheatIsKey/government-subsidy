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
    @DisplayName("SYNC-6-F13 5초를 기다리는 도중에 다른 스레드가 중단 신호 → 바로 IllegalStateException, 중단 표시는 되살아남 (대기 중 catch 경로)")
    void f13_interruptArrivingDuringSleepFailsFastAndRestoresFlag() throws Exception {
        Thread sleeping = Thread.currentThread();
        Thread interrupter = new Thread(() -> {
            try {
                Thread.sleep(100); // 확실히 잠든 뒤에 신호를 보낸다
            } catch (InterruptedException ignored) {
                return;
            }
            sleeping.interrupt();
        });
        long start = System.nanoTime();
        interrupter.start();

        Throwable thrown = catchThrowable(() -> sleeper.sleep(Duration.ofSeconds(5)));
        interrupter.join();

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        assertThat(Thread.interrupted()).as("중단 표시 (InterruptedException이 지운 것을 되살림)").isTrue();
    }

    @Test
    @DisplayName("SYNC-6-F13 (짝) 중단 표시가 없으면 300ms를 실제로 기다린다 (첫 호출의 클래스 적재 시간에 기대지 않도록 충분히 길게)")
    void f13_sleepActuallyWaits() {
        sleeper.sleep(Duration.ofMillis(1)); // 첫 호출 준비 비용을 측정에서 뺀다
        long start = System.nanoTime();

        sleeper.sleep(Duration.ofMillis(300));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(300));
    }

    @Test
    @DisplayName("SYNC-6-F13 간격 0초여도 중단 표시가 켜져 있으면 → IllegalStateException, 중단 표시 유지 (JDK sleep(0)은 중단을 확인하지 않음)")
    void f13_interruptedZeroSleepStillFails() {
        Thread.currentThread().interrupt();

        Throwable thrown = catchThrowable(() -> sleeper.sleep(Duration.ZERO));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(Thread.interrupted()).as("중단 표시").isTrue();
    }

    @Test
    @DisplayName("SYNC-6-F13 (짝) 중단 표시가 없으면 0초 대기는 예외 없이 바로 지나감")
    void f13_zeroSleepPassesWithoutInterrupt() {
        assertThatCode(() -> sleeper.sleep(Duration.ZERO)).doesNotThrowAnyException();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
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
