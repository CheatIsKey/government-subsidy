package io.github.cheatiskey.subsidy.ingestion;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** 목록(serviceList)을 1페이지부터 totalCount로 계산한 마지막 페이지까지 모은다 (SYNC-1). */
public class ServiceListCollector {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SERVICE_ID = "서비스ID";

    /** 무작위 지연 비율 (설정이 아닌 상수, SYNC-6-B3). */
    private static final double JITTER_RATIO = 0.2;

    private static final Logger log = LoggerFactory.getLogger(ServiceListCollector.class);

    private final ServiceListSource source;
    private final Gov24RetryProperties retry;
    private final Sleeper sleeper;
    private final DoubleSupplier random;

    /** 재시도 없이(호출 1번) 모은다. */
    public ServiceListCollector(ServiceListSource source) {
        this(source, new Gov24RetryProperties(1, List.of(Duration.ZERO)), duration -> { }, () -> 0.0);
    }

    /** 한 페이지 호출을 retry 설정대로 다시 부른다 (SYNC-6). random은 [0, 1) 값을 준다(무작위 지연). */
    public ServiceListCollector(
            ServiceListSource source, Gov24RetryProperties retry, Sleeper sleeper, DoubleSupplier random) {
        this.source = source;
        this.retry = retry;
        this.sleeper = sleeper;
        this.random = random;
    }

    /**
     * 목록을 처음부터 끝까지 모은다. 페이지 하나를 처리할 때마다 listener에 알린다
     * (실행 기록을 페이지마다 커밋하기 위해, SYNC-9-E1).
     * 공공 API 실패는 던지지 않고 결과에 남긴다. 401·403이나 1페이지 실패면 멈추고 stop을 채운다 (SYNC-6).
     * 공공 API가 아닌 예외는 재시도 없이 그대로 던진다 (SYNC-6-F12).
     */
    public ListCollectionResult collect(int perPage, PageListener listener) {
        Run run = new Run(listener);

        Fetched first = fetch(1, perPage);
        if (first.error() != null) {
            run.failCall(1, null, first);
            return run.result(null, stop(first.error(), "SOURCE_UNAVAILABLE"));
        }
        ParsedPage firstPage = parse(first.body());
        if (firstPage == null || firstPage.totalCount() == null) {
            // SYNC-1-E4: 1페이지를 믿을 수 없으면 totalCount를 모르므로 여기서 멈춘다.
            run.fail(1, null);
            return run.result(null, null);
        }

        int totalCount = firstPage.totalCount();
        run.accept(1, totalCount, firstPage);
        int lastPage = (int) ((totalCount + (long) perPage - 1) / perPage);
        for (int page = 2; page <= lastPage; page++) {
            Fetched fetched = fetch(page, perPage);
            if (fetched.error() == null) {
                run.accept(page, totalCount, parse(fetched.body()));
                continue;
            }
            run.failCall(page, totalCount, fetched);
            if (isAuthFailure(fetched.error())) {
                return run.result(totalCount, stop(fetched.error(), null));
            }
        }
        return run.result(totalCount, null);
    }

    /** 한 페이지를 부른다. 재시도 대상이면 retry 설정대로 기다렸다 다시 부르고, 마지막 실패 뒤에는 기다리지 않는다. */
    private Fetched fetch(int page, int perPage) {
        for (int attempt = 1; ; attempt++) {
            try {
                return new Fetched(source.fetchPage(page, perPage), null, attempt);
            } catch (Gov24ApiException e) {
                if (!isRetryable(e) || attempt >= retry.maxAttempts()) {
                    return new Fetched(null, e, attempt);
                }
                log.warn("목록 page={} 호출 {}/{}번째 실패, 다시 부름: {}", page, attempt, retry.maxAttempts(), e.getMessage());
                sleeper.sleep(withJitter(retry.intervals().get(attempt - 1)));
            }
        }
    }

    /** 간격 × (1 + 0.2 × 난수). */
    private Duration withJitter(Duration interval) {
        return Duration.ofNanos(Math.round(interval.toNanos() * (1 + JITTER_RATIO * random.getAsDouble())));
    }

    /** 재시도 대상: HTTP 5xx·429·408, TIMEOUT, CONNECTION, IO. */
    private static boolean isRetryable(Gov24ApiException e) {
        if (e.kind() != Gov24ApiException.Kind.HTTP_ERROR) {
            return true;
        }
        int status = e.httpStatus();
        return status >= 500 || status == 429 || status == 408;
    }

    private static boolean isAuthFailure(Gov24ApiException e) {
        return e.kind() == Gov24ApiException.Kind.HTTP_ERROR && (e.httpStatus() == 401 || e.httpStatus() == 403);
    }

    /** 401·403이면 AUTH_FAILED, 아니면 otherwise. */
    private static ListCollectionResult.Stop stop(Gov24ApiException e, String otherwise) {
        return new ListCollectionResult.Stop(isAuthFailure(e) ? "AUTH_FAILED" : otherwise, e.getMessage());
    }

    /** 한 페이지 호출의 끝 결과: 본문, 또는 끝내 실패한 예외. attempts는 부른 횟수. */
    private record Fetched(String body, Gov24ApiException error, int attempts) {
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

        /** 공공 API 호출이 끝내 실패한 페이지. 상태 코드(있을 때만), 종류, 부른 횟수, 예외 메시지를 남긴다. */
        void failCall(int page, Integer totalCount, Fetched fetched) {
            Gov24ApiException e = fetched.error();
            failedPages.add(page);
            listener.onPage(new CollectedPage(page, totalCount, List.of(), true,
                    new PageFailure(e.httpStatus(), e.kind().name(), fetched.attempts()), e.getMessage()));
        }

        /** 같은 서비스ID는 처음 나온 1건만 남긴다 (SYNC-1-E1). 서비스ID가 없는 건은 검사 단계로 넘긴다. */
        private boolean isDuplicate(Map<String, Object> item) {
            Object id = item.get(SERVICE_ID);
            if (!(id instanceof String s) || s.isEmpty()) {
                return false;
            }
            return !seenIds.add(s);
        }

        ListCollectionResult result(Integer sourceTotalCount, ListCollectionResult.Stop stop) {
            return new ListCollectionResult(sourceTotalCount, items, duplicateCount, failedPages, stop);
        }
    }
}
