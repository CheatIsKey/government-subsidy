package io.github.cheatiskey.subsidy.ingestion;

import java.time.Instant;

/** 목록(serviceList) 한 건을 정리한 결과. 컬럼 대응은 docs/domain.md 2.1. */
public record NormalizedBenefit(
        String serviceId,
        String name,
        String purposeSummary,
        String target,
        String selectionCriteria,
        String supportContent,
        String applyMethod,
        String applyDeadline,
        String applyDeadlineType,
        String supportType,
        String serviceField,
        String userType,
        String agencyType,
        String agencyName,
        String agencyCode,
        String department,
        String receptionOrg,
        String contactPhone,
        String detailUrl,
        Long viewCount,
        Instant sourceRegisteredAt,
        Instant listModifiedAt,
        String rawJson) {
}
