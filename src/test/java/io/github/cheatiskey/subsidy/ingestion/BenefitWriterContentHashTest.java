package io.github.cheatiskey.subsidy.ingestion;

import static io.github.cheatiskey.subsidy.ingestion.Gov24Fixtures.id;
import static io.github.cheatiskey.subsidy.ingestion.Gov24Fixtures.item;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SYNC-1-H 내용 지문(BenefitWriter.contentHash) (단위).
 * 한 건은 실제 표본 첫 건을 BenefitNormalizer로 정리해 만든다. 원본 칸 하나만 바꿔 지문을 비교한다.
 */
class BenefitWriterContentHashTest {

    private final BenefitNormalizer normalizer = new BenefitNormalizer();

    /** 표본 한 건(서비스ID id(1))을 고쳐서 정리하고 그 지문을 낸다. */
    private String hashOf(Consumer<Map<String, Object>> change) {
        Map<String, Object> raw = item(id(1), "서비스 1");
        change.accept(raw);
        List<NormalizedBenefit> accepted = normalizer.normalize(List.of(raw)).accepted();
        assertThat(accepted).hasSize(1);
        return BenefitWriter.contentHash(accepted.get(0));
    }

    private String viewCountHash(Object viewCount) {
        return hashOf(it -> it.put("조회수", viewCount));
    }

    @Test
    @DisplayName("SYNC-1-H5 조회수만 다른 두 건(1000, 1050)은 지문이 같다")
    void h5_viewCountOnlyDifferenceKeepsHash() {
        assertThat(viewCountHash(1050)).isEqualTo(viewCountHash(1000));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "서비스ID", "서비스명", "서비스목적요약", "지원대상", "선정기준", "지원내용", "신청방법", "신청기한",
            "신청기한유형", "지원유형", "서비스분야", "사용자구분", "소관기관유형", "소관기관명", "소관기관코드",
            "부서명", "접수기관", "전화문의", "상세조회URL"})
    @DisplayName("SYNC-1-H5 조회수 말고 지문 칸(글자) 하나만 다르면 지문이 다르다")
    void h5_textFieldDifferenceChangesHash(String field) {
        String base = hashOf(it -> { });

        String changed = hashOf(it -> it.put(field, it.get(field) + " 바뀜"));

        assertThat(changed).isNotEqualTo(base);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"등록일시", "수정일시"})
    @DisplayName("SYNC-1-H5 조회수 말고 지문 칸(날짜) 하나만 다르면 지문이 다르다")
    void h5_dateFieldDifferenceChangesHash(String field) {
        String base = hashOf(it -> { });

        // 표본 값(20201217142613, 20260430093900)과 다른, 해석 가능한 날짜
        String changed = hashOf(it -> it.put(field, "20250101000000"));

        assertThat(changed).isNotEqualTo(base);
    }

    static List<Arguments> viewCountBoundaries() {
        return List.of(
                Arguments.of(null, 0),
                Arguments.of(0, null),
                Arguments.of(1234, "abc")); // "abc"는 해석 못 해 null로 정리된다 (SYNC-5-B1)
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @MethodSource("viewCountBoundaries")
    @DisplayName("SYNC-1-H6 조회수가 null↔0, 1234→해석 못 함(null)으로만 바뀌면 지문이 같다")
    void h6_viewCountBoundaryKeepsHash(Object before, Object after) {
        assertThat(viewCountHash(after)).isEqualTo(viewCountHash(before));
    }
}
