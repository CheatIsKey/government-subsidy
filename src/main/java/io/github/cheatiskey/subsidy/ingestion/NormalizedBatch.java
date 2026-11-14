package io.github.cheatiskey.subsidy.ingestion;

import java.util.List;

// 껍데기(2단계 테스트 컴파일용). 로직 없음. 3단계에서 구현한다.
public record NormalizedBatch(
        List<NormalizedBenefit> accepted,
        List<RejectedItem> rejected,
        List<NormalizationWarning> warnings) {
}
