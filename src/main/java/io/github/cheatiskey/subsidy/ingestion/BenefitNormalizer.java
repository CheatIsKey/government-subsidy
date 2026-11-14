package io.github.cheatiskey.subsidy.ingestion;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/** 목록 응답의 건들을 검사·정리한다 (SYNC-5). 입력(rawItems와 그 안의 Map)은 바꾸지 않는다. */
public class BenefitNormalizer {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Pattern DATE_DIGITS = Pattern.compile("\\d{14}");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern VIEW_COUNT = Pattern.compile("\\d+|\\d{1,3}(,\\d{3})+");

    static final String REGISTERED_AT = "등록일시";
    static final String MODIFIED_AT = "수정일시";

    public NormalizedBatch normalize(List<Map<String, Object>> rawItems) {
        List<NormalizedBenefit> accepted = new ArrayList<>();
        List<RejectedItem> rejected = new ArrayList<>();
        List<NormalizationWarning> warnings = new ArrayList<>();
        for (Map<String, Object> raw : rawItems) {
            String serviceId = text(raw, "서비스ID");
            String name = text(raw, "서비스명");
            if (serviceId == null) {
                rejected.add(new RejectedItem(SyncStage.VALIDATE, null, "서비스ID 없음"));
            } else if (name == null) {
                rejected.add(new RejectedItem(SyncStage.VALIDATE, serviceId, "서비스명 없음"));
            } else {
                accepted.add(toBenefit(serviceId, name, raw, warnings));
            }
        }
        return new NormalizedBatch(accepted, rejected, warnings);
    }

    private static NormalizedBenefit toBenefit(
            String serviceId, String name, Map<String, Object> raw, List<NormalizationWarning> warnings) {
        return new NormalizedBenefit(
                serviceId,
                name,
                text(raw, "서비스목적요약"),
                text(raw, "지원대상"),
                text(raw, "선정기준"),
                text(raw, "지원내용"),
                text(raw, "신청방법"),
                text(raw, "신청기한"),
                text(raw, "신청기한유형"),
                text(raw, "지원유형"),
                text(raw, "서비스분야"),
                text(raw, "사용자구분"),
                text(raw, "소관기관유형"),
                text(raw, "소관기관명"),
                text(raw, "소관기관코드"),
                text(raw, "부서명"),
                text(raw, "접수기관"),
                text(raw, "전화문의"),
                text(raw, "상세조회URL"),
                viewCount(raw.get("조회수")),
                date(serviceId, raw, REGISTERED_AT, warnings),
                date(serviceId, raw, MODIFIED_AT, warnings),
                JSON.writeValueAsString(raw));
    }

    /** 키 없음, null, 빈 문자열은 모두 null (SYNC-5-E3). HTML 등 나머지는 원문 그대로 (SYNC-5-E9). */
    private static String text(Map<String, Object> raw, String field) {
        Object value = raw.get(field);
        if (value == null) {
            return null;
        }
        String s = value.toString();
        return s.isEmpty() ? null : s;
    }

    /** yyyyMMddHHmmss를 한국 시간으로 엄격하게 해석한다. 해석할 수 없으면 null과 경고 (SYNC-5-E4). */
    private static Instant date(
            String serviceId, Map<String, Object> raw, String field, List<NormalizationWarning> warnings) {
        String value = text(raw, field);
        if (value == null) {
            return null;
        }
        if (DATE_DIGITS.matcher(value).matches()) {
            try {
                return LocalDateTime.parse(value, DATE).atZone(KST).toInstant();
            } catch (DateTimeParseException ignored) {
                // 없는 날짜(13월, 2월 31일 등)는 아래에서 경고로 남긴다.
            }
        }
        warnings.add(new NormalizationWarning(serviceId, field, value));
        return null;
    }

    /** 숫자이거나 "1234", "1,234" 꼴이면 숫자로, 아니면 null (SYNC-5-B1). */
    private static Long viewCount(Object value) {
        if (value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        String s = value instanceof BigInteger ? value.toString() : value instanceof String str ? str : null;
        if (s == null || !VIEW_COUNT.matcher(s).matches()) {
            return null;
        }
        try {
            return Long.parseLong(s.replace(",", ""));
        } catch (NumberFormatException tooLarge) {
            return null;
        }
    }
}
