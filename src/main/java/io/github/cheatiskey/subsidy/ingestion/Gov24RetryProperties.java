package io.github.cheatiskey.subsidy.ingestion;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 목록 호출 재시도 설정 (SYNC-6, `subsidy.gov24.retry.*`). 기본: 최대 4번, 간격 1s·4s·16s.
 * 간격은 (maxAttempts−1)개 이상이어야 하고 남는 간격은 무시한다. 잘못된 값이면 기동 실패 (SYNC-6-B4).
 * 무작위 지연 비율은 설정이 아닌 상수 20%: 기다림 = 간격 × (1 + 0.2 × 난수), 난수는 [0, 1).
 */
@ConfigurationProperties("subsidy.gov24.retry")
public record Gov24RetryProperties(
        @DefaultValue("4") int maxAttempts,
        @DefaultValue({"1s", "4s", "16s"}) List<Duration> intervals) {

    public Gov24RetryProperties {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException(
                    "subsidy.gov24.retry.max-attempts(maxAttempts)는 1 이상이어야 합니다: " + maxAttempts);
        }
        if (intervals == null || intervals.isEmpty()) {
            throw new IllegalArgumentException("subsidy.gov24.retry.intervals(intervals)가 비어 있으면 안 됩니다");
        }
        if (intervals.stream().anyMatch(Duration::isNegative)) {
            throw new IllegalArgumentException(
                    "subsidy.gov24.retry.intervals(intervals)에 음수 간격이 있으면 안 됩니다: " + intervals);
        }
        if (intervals.size() < maxAttempts - 1) {
            throw new IllegalArgumentException("subsidy.gov24.retry.intervals(intervals)는 max-attempts−1 = "
                    + (maxAttempts - 1) + "개 이상이어야 합니다: " + intervals.size() + "개");
        }
        intervals = List.copyOf(intervals);
    }
}
