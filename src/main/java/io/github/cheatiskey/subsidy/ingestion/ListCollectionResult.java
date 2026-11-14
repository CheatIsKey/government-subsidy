package io.github.cheatiskey.subsidy.ingestion;

import java.util.List;
import java.util.Map;

// 껍데기(2단계 테스트 컴파일용). 로직 없음. 3단계에서 구현한다.
public record ListCollectionResult(
        Integer sourceTotalCount,
        List<Map<String, Object>> items,
        int duplicateCount,
        List<Integer> failedPages) {
}
