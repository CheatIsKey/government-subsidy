package io.github.cheatiskey.subsidy.ingestion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import tools.jackson.databind.json.JsonMapper;

/**
 * 배포 전(hash_version=1) 지문을 테스트에서 다시 만든다 (SYNC-1-H8~H12 준비용).
 * 이슈 #5 수정 전 BenefitWriter.contentHash를 그대로 옮겼다: 서비스ID + 목록 내용 21칸(조회수 포함)의 SHA-256.
 *
 * <p>임의의 문자열 대신 진짜 옛 지문을 쓰는 이유: 운영 DB의 hash_version=1 행은 이 값을 갖고 있다.
 * "옛 지문은 조회수를 포함하므로 새 지문과 다르다 → 따로 다시 계산하지 않아도 한 번 updated가 된다"는 결정(SYNC-1-H 결정)을
 * 실제와 같은 데이터로 확인하려면 옛 규칙대로 만든 값이어야 한다.
 */
final class ContentHashV1 {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ContentHashV1() {
    }

    static String of(NormalizedBenefit b) {
        Object[] values = {
                b.serviceId(), b.name(), b.purposeSummary(), b.target(), b.selectionCriteria(), b.supportContent(),
                b.applyMethod(), b.applyDeadline(), b.applyDeadlineType(), b.supportType(), b.serviceField(),
                b.userType(), b.agencyType(), b.agencyName(), b.agencyCode(), b.department(), b.receptionOrg(),
                b.contactPhone(), b.detailUrl(), b.viewCount(), text(b.sourceRegisteredAt()), text(b.listModifiedAt())};
        byte[] canonical = JSON.writeValueAsString(Arrays.asList(values)).getBytes(StandardCharsets.UTF_8);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String text(Instant t) {
        return t == null ? null : t.toString();
    }
}
