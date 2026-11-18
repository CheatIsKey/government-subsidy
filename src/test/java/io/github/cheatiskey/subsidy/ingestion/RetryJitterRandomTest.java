package io.github.cheatiskey.subsidy.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.DoubleSupplier;
import java.util.stream.DoubleStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SYNC-6-B3 운영 무작위 지연 난수 빈(IngestionConfig.retryJitterRandom)의 범위 (단위). */
class RetryJitterRandomTest {

    private final DoubleSupplier random = new IngestionConfig().retryJitterRandom();

    @Test
    @DisplayName("SYNC-6-B3 운영 난수는 [0, 1): 1만 번 뽑아 모두 0 이상 1 미만이고, 한 값으로 고정돼 있지 않다")
    void b3_productionJitterIsInUnitRange() {
        double[] draws = DoubleStream.generate(random::getAsDouble).limit(10_000).toArray();

        assertThat(DoubleStream.of(draws).min().orElseThrow()).as("가장 작은 값").isGreaterThanOrEqualTo(0.0);
        assertThat(DoubleStream.of(draws).max().orElseThrow()).as("가장 큰 값").isLessThan(1.0);
        assertThat(DoubleStream.of(draws).distinct().count()).as("서로 다른 값").isGreaterThan(1);
    }
}
