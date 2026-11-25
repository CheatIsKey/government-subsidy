package io.github.cheatiskey.subsidy.ingestion;

import static io.github.cheatiskey.subsidy.ingestion.JdbcTimes.toDb;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * 정리된 혜택을 benefit에 반영한다. 내용 지문(content_hash)이 같으면 그대로, 다르면 갱신, 없으면 추가 (SYNC-1-N1, N2).
 * 호출한 쪽의 트랜잭션에 참여한다.
 */
@Repository
class BenefitWriter {

    static final int HASH_VERSION = 2;

    /** 목록(serviceList)에서 오는 내용 컬럼. 순서는 {@link #contentValues}와 같다. */
    private static final List<String> CONTENT_COLUMNS = List.of(
            "name", "purpose_summary", "target", "selection_criteria", "support_content", "apply_method",
            "apply_deadline", "apply_deadline_type", "support_type", "service_field", "user_type", "agency_type",
            "agency_name", "agency_code", "department", "reception_org", "contact_phone", "detail_url",
            "view_count", "source_registered_at", "list_modified_at");

    private static final String INSERT = "insert into benefit (service_id, " + String.join(", ", CONTENT_COLUMNS)
            + ", content_hash, hash_version, first_seen_at, last_seen_at, created_at, updated_at) values ("
            + "?, ".repeat(CONTENT_COLUMNS.size() + 6) + "?)";

    private static final String UPDATE = "update benefit set "
            + CONTENT_COLUMNS.stream().map(c -> c + " = ?").collect(Collectors.joining(", "))
            + ", content_hash = ?, hash_version = ?, last_seen_at = ?, updated_at = ?, version = version + 1"
            + " where service_id = ?";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;

    BenefitWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record WriteCounts(int created, int updated, int unchanged) {
    }

    WriteCounts write(List<NormalizedBenefit> benefits, Instant now) {
        Map<String, String> existingHashes = new HashMap<>();
        jdbc.query("select service_id, content_hash from benefit",
                rs -> {
                    existingHashes.put(rs.getString("service_id"), rs.getString("content_hash"));
                });

        List<Object[]> inserts = new ArrayList<>();
        List<Object[]> updates = new ArrayList<>();
        List<Object[]> unchanged = new ArrayList<>();
        for (NormalizedBenefit benefit : benefits) {
            String hash = contentHash(benefit);
            if (!existingHashes.containsKey(benefit.serviceId())) {
                inserts.add(concat(new Object[] {benefit.serviceId()}, dbValues(contentValues(benefit)),
                        new Object[] {hash, HASH_VERSION, toDb(now), toDb(now), toDb(now), toDb(now)}));
            } else if (!hash.equals(existingHashes.get(benefit.serviceId()))) {
                updates.add(concat(dbValues(contentValues(benefit)),
                        new Object[] {hash, HASH_VERSION, toDb(now), toDb(now), benefit.serviceId()}));
            } else {
                unchanged.add(new Object[] {toDb(now), benefit.viewCount(), benefit.serviceId()});
            }
        }
        jdbc.batchUpdate(INSERT, inserts);
        jdbc.batchUpdate(UPDATE, updates);
        jdbc.batchUpdate("update benefit set last_seen_at = ?, view_count = ? where service_id = ?", unchanged);
        return new WriteCounts(inserts.size(), updates.size(), unchanged.size());
    }

    private static Object[] contentValues(NormalizedBenefit b) {
        return new Object[] {
                b.name(), b.purposeSummary(), b.target(), b.selectionCriteria(), b.supportContent(),
                b.applyMethod(), b.applyDeadline(), b.applyDeadlineType(), b.supportType(), b.serviceField(),
                b.userType(), b.agencyType(), b.agencyName(), b.agencyCode(), b.department(), b.receptionOrg(),
                b.contactPhone(), b.detailUrl(), b.viewCount(), b.sourceRegisteredAt(), b.listModifiedAt()};
    }

    /** 지문에 넣는 값: 내용 값에서 조회수(view_count)를 뺀다. 조회수는 자주 변해서 지문에 넣으면 안 된다 (SYNC-1-H). */
    private static Object[] hashedValues(NormalizedBenefit b) {
        Object[] all = contentValues(b);
        int skip = CONTENT_COLUMNS.indexOf("view_count");
        List<Object> kept = new ArrayList<>();
        for (int i = 0; i < all.length; i++) {
            if (i != skip) {
                kept.add(all[i]);
            }
        }
        return kept.toArray();
    }

    private static Object[] dbValues(Object[] values) {
        return Arrays.stream(values).map(v -> v instanceof Instant t ? toDb(t) : v).toArray();
    }

    /** 정리된 내용 컬럼의 SHA-256. 원본 JSON(rawJson)은 넣지 않는다. */
    static String contentHash(NormalizedBenefit b) {
        Object[] values = Arrays.stream(concat(new Object[] {b.serviceId()}, hashedValues(b)))
                .map(v -> v instanceof Instant t ? t.toString() : v)
                .toArray();
        byte[] canonical = JSON.writeValueAsString(Arrays.asList(values)).getBytes(StandardCharsets.UTF_8);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object[] concat(Object[]... parts) {
        return Arrays.stream(parts).flatMap(Arrays::stream).toArray();
    }
}
