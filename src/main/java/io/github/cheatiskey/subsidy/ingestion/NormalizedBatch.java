package io.github.cheatiskey.subsidy.ingestion;

import java.util.List;

public record NormalizedBatch(
        List<NormalizedBenefit> accepted,
        List<RejectedItem> rejected,
        List<NormalizationWarning> warnings) {
}
