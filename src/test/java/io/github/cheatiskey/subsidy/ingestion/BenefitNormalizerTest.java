package io.github.cheatiskey.subsidy.ingestion;

import static io.github.cheatiskey.subsidy.ingestion.Gov24Fixtures.SAMPLE_ITEM;
import static io.github.cheatiskey.subsidy.ingestion.Gov24Fixtures.id;
import static io.github.cheatiskey.subsidy.ingestion.Gov24Fixtures.item;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** SYNC-5 응답 검사·정리 (단위). 한 건의 모양은 실제 표본 첫 건을 바탕으로 한다. */
class BenefitNormalizerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final BenefitNormalizer normalizer = new BenefitNormalizer();

    /** 표본 한 건(서비스ID id(1))을 고쳐서 하나만 정리해 본다. */
    private NormalizedBatch normalizeOne(Consumer<Map<String, Object>> change) {
        Map<String, Object> raw = item(id(1), "서비스 1");
        change.accept(raw);
        return normalizer.normalize(List.of(raw));
    }

    /** 그 한 건이 저장 대상으로 남았는지 확인하고 꺼낸다. */
    private NormalizedBenefit onlyAccepted(NormalizedBatch batch) {
        assertThat(batch.accepted()).extracting(NormalizedBenefit::serviceId).containsExactly(id(1));
        return batch.accepted().get(0);
    }

    /** 필수 필드를 "키 없음 / null / 빈 문자열"로 만든다. */
    private static void blank(Map<String, Object> raw, String field, String form) {
        switch (form) {
            case "키 없음" -> raw.remove(field);
            case "null" -> raw.put(field, null);
            default -> raw.put(field, "");
        }
    }

    private static Instant kst(int y, int mo, int d, int h, int mi, int s) {
        return ZonedDateTime.of(y, mo, d, h, mi, s, 0, KST).toInstant();
    }

    private static String text(String field) {
        return (String) SAMPLE_ITEM.get(field);
    }

    @Test
    @DisplayName("SYNC-5-N1 실제 표본 1건을 정리하면 22개 필드가 모두 정해진 칸에 정확히 들어간다")
    void n1_sampleItemMapsAllFields() {
        NormalizedBatch batch = normalizer.normalize(List.of(SAMPLE_ITEM));

        NormalizedBenefit expected = new NormalizedBenefit(
                "105100000001",
                "근로·자녀장려금",
                text("서비스목적요약"),
                text("지원대상"),
                text("선정기준"),
                text("지원내용"),
                "기타 온라인신청",
                text("신청기한"),
                "직접입력",
                "현금",
                "주거·자립",
                "가구",
                "중앙행정기관",
                "국세청",
                "1210000",
                "장려세제과",
                "관할 세무서",
                "해당지역 지자체(세무서)/관할 세무서",
                text("상세조회URL"),
                2434939L,
                kst(2020, 12, 17, 14, 26, 13),
                kst(2026, 4, 30, 9, 39, 0),
                null);
        assertThat(batch.accepted()).hasSize(1);
        assertThat(batch.accepted().get(0)).usingRecursiveComparison().ignoringFields("rawJson").isEqualTo(expected);
        assertThat(batch.accepted().get(0).rawJson()).as("원본 JSON").isNotNull();
        assertThat(Gov24Fixtures.JSON.readValue(batch.accepted().get(0).rawJson(), Map.class)).isEqualTo(SAMPLE_ITEM);
        assertThat(batch.rejected()).isEmpty();
        assertThat(batch.warnings()).isEmpty();
    }

    @ParameterizedTest(name = "[{index}] 서비스ID {0}")
    @ValueSource(strings = {"키 없음", "null", "빈 문자열"})
    @DisplayName("SYNC-5-E1 서비스ID가 없는 건은 빼고 VALIDATE 실패로 기록, 나머지는 계속")
    void e1_missingServiceId(String form) {
        Map<String, Object> noId = item(id(2), "서비스 2");
        blank(noId, "서비스ID", form);

        NormalizedBatch batch = normalizer.normalize(List.of(item(id(1), "서비스 1"), noId, item(id(3), "서비스 3")));

        assertThat(batch.accepted()).extracting(NormalizedBenefit::serviceId).containsExactly(id(1), id(3));
        assertThat(batch.rejected()).extracting(RejectedItem::stage, RejectedItem::serviceId)
                .containsExactly(tuple(SyncStage.VALIDATE, null));
    }

    @ParameterizedTest(name = "[{index}] 서비스명 {0}")
    @ValueSource(strings = {"키 없음", "null", "빈 문자열"})
    @DisplayName("SYNC-5-E2 서비스명이 없는 건은 빼고 VALIDATE 실패로 기록, 나머지는 계속")
    void e2_missingName(String form) {
        Map<String, Object> noName = item(id(2), "서비스 2");
        blank(noName, "서비스명", form);

        NormalizedBatch batch = normalizer.normalize(List.of(item(id(1), "서비스 1"), noName, item(id(3), "서비스 3")));

        assertThat(batch.accepted()).extracting(NormalizedBenefit::serviceId).containsExactly(id(1), id(3));
        assertThat(batch.rejected()).extracting(RejectedItem::stage, RejectedItem::serviceId)
                .containsExactly(tuple(SyncStage.VALIDATE, id(2)));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"빈 문자열", "null", "키 없음", "값 있음"})
    @DisplayName("SYNC-5-E3 선택 필드(선정기준)가 \"\", null, 키 없음이면 셋 다 null, 값이 있으면 그대로")
    void e3_blankOptionalFieldBecomesNull(String form) {
        NormalizedBatch batch = normalizeOne(raw -> {
            if (form.equals("값 있음")) {
                raw.put("선정기준", "소득 하위 50%");
            } else {
                blank(raw, "선정기준", form);
            }
        });

        String expected = form.equals("값 있음") ? "소득 하위 50%" : null;
        NormalizedBenefit benefit = onlyAccepted(batch);
        assertThat(benefit.selectionCriteria()).isEqualTo(expected);
        assertThat(benefit.target()).as("다른 선택 필드는 그대로").isEqualTo(text("지원대상"));
    }

    @Test
    @DisplayName("SYNC-5-E4 날짜 yyyyMMddHHmmss는 한국 시간(Asia/Seoul)으로 해석")
    void e4_validDateIsKst() {
        NormalizedBenefit benefit = onlyAccepted(normalizeOne(raw -> {
            raw.put("등록일시", "20201217142613");
            raw.put("수정일시", "20260430093900");
        }));

        assertThat(benefit.sourceRegisteredAt()).isEqualTo(kst(2020, 12, 17, 14, 26, 13));
        assertThat(benefit.listModifiedAt()).isEqualTo(kst(2026, 4, 30, 9, 39, 0));
    }

    static List<Arguments> badDates() {
        return List.of(
                Arguments.of("수정일시", "2026-04-30 09:39:00"),
                Arguments.of("수정일시", "202604300939"),       // 12자리
                Arguments.of("수정일시", "2026043009390012"),   // 16자리
                Arguments.of("수정일시", "20261301000000"),     // 13월
                Arguments.of("수정일시", "20260231000000"),     // 2월 31일 (엄격 해석)
                Arguments.of("등록일시", "20201217"));          // 8자리
    }

    @ParameterizedTest(name = "[{index}] {0}={1}")
    @MethodSource("badDates")
    @DisplayName("SYNC-5-E4 날짜가 14자리가 아니거나 없는 날짜면 그 필드만 null, 경고(API 필드 이름), 건은 저장")
    void e4_invalidDateBecomesNullWithWarning(String field, String badDate) {
        NormalizedBatch batch = normalizeOne(raw -> raw.put(field, badDate));

        NormalizedBenefit benefit = onlyAccepted(batch);
        if (field.equals("수정일시")) {
            assertThat(benefit.listModifiedAt()).isNull();
            assertThat(benefit.sourceRegisteredAt()).isEqualTo(kst(2020, 12, 17, 14, 26, 13));
        } else {
            assertThat(benefit.sourceRegisteredAt()).isNull();
            assertThat(benefit.listModifiedAt()).isEqualTo(kst(2026, 4, 30, 9, 39, 0));
        }
        assertThat(batch.warnings()).containsExactly(new NormalizationWarning(id(1), field, badDate));
    }

    @Test
    @DisplayName("SYNC-5-E6 모르는 새 필드는 무시하고 원본 JSON에 보존")
    void e6_unknownFieldKeptInRawJson() {
        NormalizedBatch batch = normalizeOne(raw -> raw.put("새필드", "새 값"));

        String raw = onlyAccepted(batch).rawJson();
        assertThat(raw).as("원본 JSON").isNotNull();
        Map<?, ?> rawJson = Gov24Fixtures.JSON.readValue(raw, Map.class);
        assertThat(rawJson.get("새필드")).isEqualTo("새 값");
        assertThat(rawJson.get("서비스ID")).isEqualTo(id(1));
    }

    @Test
    @DisplayName("SYNC-5-E9 HTML 태그와 <script>는 원문 그대로 저장 (이스케이프는 화면·응답에서)")
    void e9_htmlKeptAsIs() {
        String html = "<script>alert('x')</script><b>월 20만 원</b> 지원";

        NormalizedBenefit benefit = onlyAccepted(normalizeOne(raw -> raw.put("지원내용", html)));

        assertThat(benefit.supportContent()).isEqualTo(html);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"직접입력", "상시신청", "접수기관별상이", "기간설정", "처음보는유형"})
    @DisplayName("SYNC-5-E10 신청기한유형은 apply_deadline_type에 글자 그대로 저장 (모르는 값도)")
    void e10_applyDeadlineTypeStoredAsIs(String type) {
        NormalizedBenefit benefit = onlyAccepted(normalizeOne(raw -> raw.put("신청기한유형", type)));

        assertThat(benefit.applyDeadlineType()).isEqualTo(type);
    }

    static List<Arguments> viewCounts() {
        return List.of(
                Arguments.of(2434939, 2434939L),   // 실측: 숫자 타입
                Arguments.of("1,234", 1234L),
                Arguments.of("1234", 1234L),
                Arguments.of("많음", null));
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @MethodSource("viewCounts")
    @DisplayName("SYNC-5-B1 조회수는 해석할 수 있으면 숫자로, 아니면 null")
    void b1_viewCountParsing(Object raw, Long expected) {
        NormalizedBenefit benefit = onlyAccepted(normalizeOne(it -> it.put("조회수", raw)));

        assertThat(benefit.viewCount()).isEqualTo(expected);
    }
}
