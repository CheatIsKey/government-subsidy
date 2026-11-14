package io.github.cheatiskey.subsidy;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 운영용 시계 빈. 완성된 설정이다. 테스트는 @Primary 고정 시계로 바꿔 끼운다.
/** 시각은 주입한 Clock으로 기록한다 (docs/decisions.md). 테스트는 고정 Clock으로 바꿔 끼운다. */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
