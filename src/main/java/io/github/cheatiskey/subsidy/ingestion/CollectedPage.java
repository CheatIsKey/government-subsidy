package io.github.cheatiskey.subsidy.ingestion;

import java.util.List;
import java.util.Map;

/**
 * 페이지 하나를 처리한 결과. failed=true면 이 페이지는 실패로 기록할 대상이다.
 * failure는 공공 API 호출이 끝내 실패했을 때의 기록 칸(SYNC-6). 그 밖에는 null.
 */
public record CollectedPage(
        int page, Integer totalCount, List<Map<String, Object>> items, boolean failed, PageFailure failure) {

    // 껍데기(이슈 #6 2단계): failure 칸을 더하면서 기존 생성자를 남긴다.
    public CollectedPage(int page, Integer totalCount, List<Map<String, Object>> items, boolean failed) {
        this(page, totalCount, items, failed, null);
    }
}
