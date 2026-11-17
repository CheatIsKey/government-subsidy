package io.github.cheatiskey.subsidy.ingestion;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

// 껍데기(이슈 #6 2단계 테스트 컴파일용). 기본값·검사 없음. 3단계에서 구현한다.
/**
 * 목록 호출 재시도 설정 (SYNC-6, `subsidy.gov24.retry.*`).
 * 무작위 지연 비율은 설정이 아닌 상수 20%: 기다림 = 간격 × (1 + 0.2 × 난수), 난수는 [0, 1).
 */
@ConfigurationProperties("subsidy.gov24.retry")
public record Gov24RetryProperties(
        int maxAttempts,
        List<Duration> intervals) {
}
