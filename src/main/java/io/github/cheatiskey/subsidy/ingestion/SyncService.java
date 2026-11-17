package io.github.cheatiskey.subsidy.ingestion;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 전체(FULL) 동기화 한 번 (SYNC-1, SYNC-5, SYNC-9).
 * 이 메서드 전체를 하나의 트랜잭션으로 묶지 않는다. 실행 기록은 시작과 페이지마다 따로 커밋해
 * 다른 커넥션에서 진행 건수를 볼 수 있게 하고 (SYNC-9-E1), 혜택 반영과 실행 종료만 한 트랜잭션으로 묶는다.
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    /** 검사 실패율이 이 비율(10%)을 넘으면 SCHEMA_CHANGED로 중단한다 (Q-11, SYNC-5-B2). */
    private static final int SCHEMA_CHANGED_PERCENT = 10;

    private final ServiceListSource source;
    private final Clock clock;
    private final BenefitNormalizer normalizer = new BenefitNormalizer();

    private Gov24Properties properties;
    private Gov24RetryProperties retry;
    private Sleeper sleeper;
    private DoubleSupplier random;
    private SyncRunRecorder recorder;
    private BenefitWriter benefitWriter;
    private SyncRunQueryService queryService;
    private TransactionTemplate transaction;

    public SyncService(ServiceListSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    /** 생성자 시그니처(테스트가 기대는 계약)를 지키기 위해 나머지 협력 객체는 여기서 받는다. */
    @Autowired
    void setCollaborators(
            Gov24Properties properties,
            Gov24RetryProperties retry,
            Sleeper sleeper,
            DoubleSupplier random,
            SyncRunRecorder recorder,
            BenefitWriter benefitWriter,
            SyncRunQueryService queryService,
            PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.retry = retry;
        this.sleeper = sleeper;
        this.random = random;
        this.recorder = recorder;
        this.benefitWriter = benefitWriter;
        this.queryService = queryService;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * 전체(FULL) 동기화를 한 번 실행하고, 끝난 실행 기록을 돌려준다.
     * 공공 API 실패는 실행 기록(FAILED·PARTIAL)에 남기고 던지지 않는다. 공공 API가 아닌 예외는
     * FAILED `INTERNAL_ERROR`로 기록한 뒤 같은 예외를 다시 던진다. 어느 쪽이든 RUNNING으로 남지 않는다 (SYNC-6).
     */
    public SyncRunView runFull() {
        long runId = recorder.start(SyncType.FULL, clock.instant());
        try {
            ListCollectionResult collected = new ServiceListCollector(withHeartbeat(runId), retry, sleeper, random)
                    .collect(properties.perPage(), page -> recordPage(runId, page));
            if (collected.duplicateCount() > 0) {
                log.warn("동기화 run={} 같은 서비스ID 중복 {}건은 처음 나온 건만 남김", runId, collected.duplicateCount());
            }
            transaction.executeWithoutResult(status -> recorder.finish(runId, apply(runId, collected), clock.instant()));
        } catch (RuntimeException e) {
            recorder.finish(runId, SyncOutcome.failed("INTERNAL_ERROR", e.getClass().getName(), null, 0), clock.instant());
            throw e;
        }
        return queryService.find(runId).orElseThrow();
    }

    /** 공공 API를 부를 때마다(성공·실패 모두) heartbeat를 갱신한다 (SYNC-6-E1). */
    private ServiceListSource withHeartbeat(long runId) {
        return (page, perPage) -> {
            try {
                return source.fetchPage(page, perPage);
            } finally {
                recorder.heartbeat(runId, clock.instant());
            }
        };
    }

    /** 페이지 진행을 커밋한다. 공공 API 호출이 끝내 실패한 페이지는 ERROR 로그를 1건 남긴다(키를 가린 예외 메시지만). */
    private void recordPage(long runId, CollectedPage page) {
        recorder.recordPage(runId, page, clock.instant());
        if (page.failure() != null) {
            log.error("동기화 run={} 목록 page={} 호출 {}번 끝에 실패: {}",
                    runId, page.page(), page.failure().attemptCount(), page.failureMessage());
        }
    }

    /** 모은 목록을 검사하고, 저장할 수 있으면 혜택에 반영한 뒤 실행 결과를 정한다. */
    private SyncOutcome apply(long runId, ListCollectionResult collected) {
        Integer totalCount = collected.sourceTotalCount();
        if (collected.stop() != null) {
            // SYNC-6: 401·403 또는 1페이지 실패. 이미 받은 페이지도 저장하지 않는다.
            return SyncOutcome.failed(collected.stop().errorCode(), collected.stop().message(), totalCount, 0);
        }
        if (totalCount == null) {
            return SyncOutcome.failed(null, "1페이지 응답을 해석할 수 없어 totalCount를 모름", null, 0);
        }
        if (totalCount == 0) {
            return SyncOutcome.failed("SOURCE_EMPTY", "totalCount=0", totalCount, 0);
        }

        int fetched = collected.items().size();
        NormalizedBatch batch = normalizer.normalize(collected.items());
        batch.warnings().forEach(w ->
                log.warn("동기화 run={} 서비스ID={} {} 값을 해석할 수 없어 비움: {}", runId, w.serviceId(), w.field(), w.rawValue()));
        recorder.recordRejections(runId, batch.rejected());
        int rejected = batch.rejected().size();
        if ((long) rejected * 100 > (long) fetched * SCHEMA_CHANGED_PERCENT) {
            return SyncOutcome.failed("SCHEMA_CHANGED",
                    "검사 실패 " + rejected + "/" + fetched + "건이 " + SCHEMA_CHANGED_PERCENT + "% 초과", totalCount, rejected);
        }

        BenefitWriter.WriteCounts counts = benefitWriter.write(batch.accepted(), clock.instant());
        List<String> problems = new ArrayList<>();
        if (!collected.failedPages().isEmpty()) {
            problems.add("실패 페이지 " + collected.failedPages());
        }
        if (fetched != totalCount) {
            problems.add("모은 건수 " + fetched + " ≠ totalCount " + totalCount);
        }
        SyncRunStatus status = problems.isEmpty() ? SyncRunStatus.SUCCEEDED : SyncRunStatus.PARTIAL;
        return new SyncOutcome(status, null, problems.isEmpty() ? null : String.join(", ", problems), totalCount,
                counts.created(), counts.updated(), counts.unchanged(), rejected);
    }
}
