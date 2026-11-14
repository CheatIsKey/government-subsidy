package io.github.cheatiskey.subsidy.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import tools.jackson.databind.json.JsonMapper;

/**
 * 테스트용 공공 API 응답 만들기.
 * 한 건의 모양은 실제 표본(fixtures/gov24/serviceList-page1-perPage3.json)의 첫 건을 복사해 쓴다.
 */
final class Gov24Fixtures {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String NO_DATA = load("serviceList-no-data.json");

    /** 실제 표본의 첫 건 (서비스ID 105100000001). 읽기 전용. 한 번만 읽는다. */
    static final Map<String, Object> SAMPLE_ITEM = Collections.unmodifiableMap(firstSampleItem());

    private Gov24Fixtures() {
    }

    static String load(String fileName) {
        try (InputStream in = Gov24Fixtures.class.getResourceAsStream("/fixtures/gov24/" + fileName)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstSampleItem() {
        Map<String, Object> page = JSON.readValue(load("serviceList-page1-perPage3.json"), Map.class);
        return new LinkedHashMap<>(((List<Map<String, Object>>) page.get("data")).get(0));
    }

    /** 12자리 테스트용 서비스ID. 예: id(7) = "T00000000007" */
    static String id(int n) {
        return "T%011d".formatted(n);
    }

    /** 실제 표본 첫 건의 복사본에서 서비스ID와 서비스명만 바꾼 건. 고쳐 써도 된다. */
    static Map<String, Object> item(String serviceId, String name) {
        Map<String, Object> item = new LinkedHashMap<>(SAMPLE_ITEM);
        item.put("서비스ID", serviceId);
        item.put("서비스명", name);
        return item;
    }

    /** id(from)~id(to) 건. 서비스명은 "서비스 n". */
    static List<Map<String, Object>> items(int from, int to) {
        return new ArrayList<>(IntStream.rangeClosed(from, to).mapToObj(n -> item(id(n), "서비스 " + n)).toList());
    }

    /** 실제 응답 껍데기 모양의 페이지 본문. */
    static String page(int page, int perPage, int totalCount, List<Map<String, Object>> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("currentCount", data.size());
        body.put("data", data);
        body.put("matchCount", totalCount);
        body.put("page", page);
        body.put("perPage", perPage);
        body.put("totalCount", totalCount);
        return JSON.writeValueAsString(body);
    }

    /** items를 perPage씩 잘라 페이지 번호(1부터) → 본문으로 만든다. totalCount는 모든 페이지에 같게 넣는다. */
    static Map<Integer, String> pages(int perPage, int totalCount, List<Map<String, Object>> items) {
        Map<Integer, String> pages = new LinkedHashMap<>();
        for (int start = 0, no = 1; start < items.size(); start += perPage, no++) {
            pages.put(no, page(no, perPage, totalCount, items.subList(start, Math.min(start + perPage, items.size()))));
        }
        return pages;
    }
}
