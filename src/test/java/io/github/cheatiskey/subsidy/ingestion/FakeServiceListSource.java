package io.github.cheatiskey.subsidy.ingestion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 페이지 번호별로 정해 둔 본문을 돌려주고, 어떤 페이지를 불렀는지 기록하는 가짜.
 * 정하지 않은 페이지를 부르면 테스트를 바로 실패시킨다 (불필요한 호출을 잡기 위해).
 */
class FakeServiceListSource implements ServiceListSource {

    private final Map<Integer, String> bodies = new HashMap<>();
    final List<Integer> requestedPages = new ArrayList<>();
    final List<Integer> requestedPerPages = new ArrayList<>();

    FakeServiceListSource with(Map<Integer, String> pages) {
        bodies.putAll(pages);
        return this;
    }

    FakeServiceListSource with(int page, String body) {
        bodies.put(page, body);
        return this;
    }

    @Override
    public String fetchPage(int page, int perPage) {
        requestedPages.add(page);
        requestedPerPages.add(perPage);
        String body = bodies.get(page);
        if (body == null) {
            throw new AssertionError("정하지 않은 페이지를 불렀다: page=" + page + ", 지금까지 " + requestedPages);
        }
        return body;
    }
}
