package io.github.cheatiskey.subsidy.ingestion;

import java.util.List;
import java.util.Map;

/** 수집 결과. stop은 공공 API 실패로 실행을 멈춰야 할 때만 있다 (SYNC-6). */
public record ListCollectionResult(
        Integer sourceTotalCount,
        List<Map<String, Object>> items,
        int duplicateCount,
        List<Integer> failedPages,
        Stop stop) {

    /** errorCode는 AUTH_FAILED 또는 SOURCE_UNAVAILABLE, message는 Gov24ApiException 메시지. */
    public record Stop(String errorCode, String message) {
    }
}
