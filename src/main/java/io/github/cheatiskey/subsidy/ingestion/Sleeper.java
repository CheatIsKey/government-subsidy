package io.github.cheatiskey.subsidy.ingestion;

import java.time.Duration;

/** 재시도 사이의 기다림. 테스트는 가짜로 바꿔 끼운다 (SYNC-6-B3). */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration);
}
