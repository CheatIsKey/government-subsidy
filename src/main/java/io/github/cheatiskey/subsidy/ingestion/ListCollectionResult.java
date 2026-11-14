package io.github.cheatiskey.subsidy.ingestion;

import java.util.List;
import java.util.Map;

public record ListCollectionResult(
        Integer sourceTotalCount,
        List<Map<String, Object>> items,
        int duplicateCount,
        List<Integer> failedPages) {
}
