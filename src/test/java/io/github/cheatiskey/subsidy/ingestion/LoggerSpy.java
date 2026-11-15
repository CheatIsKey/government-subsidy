package io.github.cheatiskey.subsidy.ingestion;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.LoggerFactory;

/**
 * 정한 로거들에 ListAppender를 TRACE로 붙여 남긴 로그를 직접 센다 (docs/decisions.md 로그 확인 방법).
 * {@link #close()}에서 떼고 원래 레벨로 되돌린다.
 * 로거 레벨은 JVM 전역이라, 테스트 병렬 실행을 켜면 이것을 쓰는 테스트에 {@code @Isolated}가 필요하다.
 */
final class LoggerSpy implements AutoCloseable {

    private final Map<Logger, Level> originalLevels = new LinkedHashMap<>();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LoggerSpy() {
    }

    /** 로그를 셀 로거(TRACE까지). */
    static LoggerSpy watch(Class<?>... types) {
        LoggerSpy spy = new LoggerSpy();
        spy.appender.start();
        for (Class<?> type : types) {
            Logger logger = (Logger) LoggerFactory.getLogger(type);
            spy.originalLevels.putIfAbsent(logger, logger.getLevel());
            logger.setLevel(Level.TRACE);
            logger.addAppender(spy.appender);
        }
        return spy;
    }

    /** 이 이름의 로거는 테스트 동안 끈다 (가짜 서버 로그). */
    LoggerSpy silence(String... loggerNames) {
        for (String name : loggerNames) {
            Logger logger = (Logger) LoggerFactory.getLogger(name);
            originalLevels.putIfAbsent(logger, logger.getLevel());
            logger.setLevel(Level.OFF);
        }
        return this;
    }

    List<String> events() {
        List<String> lines = new ArrayList<>();
        for (ILoggingEvent e : appender.list) {
            lines.add(e.getLoggerName() + " " + e.getLevel() + " " + e.getFormattedMessage());
        }
        return lines;
    }

    @Override
    public void close() {
        originalLevels.forEach((logger, level) -> {
            logger.detachAppender(appender);
            logger.setLevel(level);
        });
        appender.stop();
    }
}
