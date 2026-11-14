package io.github.cheatiskey.subsidy.ingestion;

import java.util.List;
import java.util.Map;

/** 페이지 하나를 처리한 결과. failed=true면 이 페이지는 실패로 기록할 대상이다. */
public record CollectedPage(int page, Integer totalCount, List<Map<String, Object>> items, boolean failed) {
}
