package io.github.cheatiskey.subsidy.ingestion;

/** 실행을 끝낼 때 sync_run에 남길 결과. rejected는 검사(VALIDATE)에서 뺀 건수. */
record SyncOutcome(
        SyncRunStatus status,
        String errorCode,
        String errorSummary,
        Integer sourceTotalCount,
        int created,
        int updated,
        int unchanged,
        int rejected) {

    /** 혜택을 하나도 저장하지 않고 끝내는 실패. */
    static SyncOutcome failed(String errorCode, String errorSummary, Integer sourceTotalCount, int rejected) {
        return new SyncOutcome(SyncRunStatus.FAILED, errorCode, errorSummary, sourceTotalCount, 0, 0, 0, rejected);
    }
}
