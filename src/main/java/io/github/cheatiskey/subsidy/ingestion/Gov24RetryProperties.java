package io.github.cheatiskey.subsidy.ingestion;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

// 껍데기(이슈 #6 2단계 테스트 컴파일용). 기본값·검사 없음. 3단계에서 구현한다.
/** 목록 호출 재시도 설정 (SYNC-6, `subsidy.gov24.retry.*`). */
@ConfigurationProperties("subsidy.gov24.retry")
public record Gov24RetryProperties(
        int maxAttempts,
        List<Duration> intervals,
        double jitterRatio) {
}
