package io.github.cheatiskey.subsidy.ingestion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 페이지마다 호출 순서대로 결과(본문 또는 예외)를 정해 두는 가짜 (SYNC-6 재시도 단위 테스트).
 * 정해 둔 결과를 다 쓰면 마지막 결과를 계속 돌려준다. 정하지 않은 페이지를 부르면 바로 실패시킨다.
 */
class ScriptedServiceListSource implements ServiceListSource {

    private final Map<Integer, List<Object>> outcomes = new HashMap<>();
    private final Map<Integer, Integer> calls = new HashMap<>();
    final List<Integer> requestedPages = new ArrayList<>();

    /** outcome은 String(본문) 또는 RuntimeException(던질 예외). */
    ScriptedServiceListSource on(int page, Object... outcome) {
        outcomes.put(page, List.of(outcome));
        return this;
    }

    ScriptedServiceListSource with(Map<Integer, String> pages) {
        pages.forEach((page, body) -> on(page, body));
        return this;
    }

    int calls(int page) {
        return calls.getOrDefault(page, 0);
    }

    @Override
    public String fetchPage(int page, int perPage) {
        requestedPages.add(page);
        int n = calls.merge(page, 1, Integer::sum);
        List<Object> list = outcomes.get(page);
        if (list == null) {
            throw new AssertionError("정하지 않은 페이지를 불렀다: page=" + page + ", 지금까지 " + requestedPages);
        }
        Object outcome = list.get(Math.min(n, list.size()) - 1);
        if (outcome instanceof RuntimeException e) {
            throw e;
        }
        return (String) outcome;
    }
}
