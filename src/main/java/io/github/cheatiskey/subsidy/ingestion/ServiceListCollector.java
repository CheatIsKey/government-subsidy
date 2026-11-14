package io.github.cheatiskey.subsidy.ingestion;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** 목록(serviceList)을 1페이지부터 totalCount로 계산한 마지막 페이지까지 모은다 (SYNC-1). */
public class ServiceListCollector {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SERVICE_ID = "서비스ID";

    private final ServiceListSource source;

    public ServiceListCollector(ServiceListSource source) {
        this.source = source;
    }

    /**
     * 목록을 처음부터 끝까지 모은다. 페이지 하나를 처리할 때마다 listener에 알린다
     * (실행 기록을 페이지마다 커밋하기 위해, SYNC-9-E1).
     */
    public ListCollectionResult collect(int perPage, PageListener listener) {
        Run run = new Run(listener);

        ParsedPage first = parse(source.fetchPage(1, perPage));
        if (first == null || first.totalCount() == null) {
            // SYNC-1-E4: 1페이지를 믿을 수 없으면 totalCount를 모르므로 여기서 멈춘다.
            run.fail(1, null);
            return run.result(null);
        }

        int totalCount = first.totalCount();
        run.accept(1, totalCount, first);
        int lastPage = (int) ((totalCount + (long) perPage - 1) / perPage);
        for (int page = 2; page <= lastPage; page++) {
            run.accept(page, totalCount, parse(source.fetchPage(page, perPage)));
        }
        return run.result(totalCount);
    }

    /** JSON 객체이고 data 배열이 있으면 해석 결과, 아니면 null (SYNC-1-E4, SYNC-5-E8). */
    @SuppressWarnings("unchecked")
    private static ParsedPage parse(String body) {
        if (body == null) {
            return null;
        }
        Object parsed;
        try {
            parsed = JSON.readValue(body, Object.class);
        } catch (JacksonException e) {
            return null;
        }
        if (!(parsed instanceof Map<?, ?> map) || !(map.get("data") instanceof List<?> data)) {
            return null;
        }
        Integer totalCount = map.get("totalCount") instanceof Integer n ? n : null;
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object item : data) {
            if (item instanceof Map<?, ?> m) {
                items.add(new LinkedHashMap<>((Map<String, Object>) m));
            }
        }
        return new ParsedPage(totalCount, items);
    }

    private record ParsedPage(Integer totalCount, List<Map<String, Object>> items) {
    }

    /** 한 번의 수집 동안 모은 건, 중복, 실패 페이지. */
    private static final class Run {

        private final PageListener listener;
        private final List<Map<String, Object>> items = new ArrayList<>();
        private final Set<String> seenIds = new HashSet<>();
        private final List<Integer> failedPages = new ArrayList<>();
        private int duplicateCount;

        Run(PageListener listener) {
            this.listener = listener;
        }

        /** 해석 실패, 또는 totalCount>0인데 data가 비었으면 그 페이지를 실패로 본다 (SYNC-1-E3). */
        void accept(int page, int totalCount, ParsedPage parsed) {
            if (parsed == null || (totalCount > 0 && parsed.items().isEmpty())) {
                fail(page, totalCount);
                return;
            }
            List<Map<String, Object>> kept = new ArrayList<>();
            for (Map<String, Object> item : parsed.items()) {
                if (isDuplicate(item)) {
                    duplicateCount++;
                } else {
                    kept.add(item);
                }
            }
            items.addAll(kept);
            listener.onPage(new CollectedPage(page, totalCount, kept, false));
        }

        void fail(int page, Integer totalCount) {
            failedPages.add(page);
            listener.onPage(new CollectedPage(page, totalCount, List.of(), true));
        }

        /** 같은 서비스ID는 처음 나온 1건만 남긴다 (SYNC-1-E1). 서비스ID가 없는 건은 검사 단계로 넘긴다. */
        private boolean isDuplicate(Map<String, Object> item) {
            Object id = item.get(SERVICE_ID);
            if (!(id instanceof String s) || s.isEmpty()) {
                return false;
            }
            return !seenIds.add(s);
        }

        ListCollectionResult result(Integer sourceTotalCount) {
            return new ListCollectionResult(sourceTotalCount, items, duplicateCount, failedPages);
        }
    }
}
