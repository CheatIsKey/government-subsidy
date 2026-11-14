package io.github.cheatiskey.subsidy.ingestion;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 운영 API: 실행 기록 조회 (SYNC-9, docs/api.md 3절). */
@RestController
@RequestMapping("/ops/v1/sync-runs")
class SyncRunController {

    private final SyncRunQueryService queryService;

    SyncRunController(SyncRunQueryService queryService) {
        this.queryService = queryService;
    }

    record Items<T>(List<T> items) {
    }

    @GetMapping
    Items<SyncRunView> list(@RequestParam(defaultValue = "20") int size) {
        return new Items<>(queryService.list(size));
    }

    @GetMapping("/{runId}")
    SyncRunView find(@PathVariable long runId) {
        return queryService.find(runId).orElseThrow(SyncRunNotFoundException::new);
    }

    @GetMapping("/{runId}/failures")
    Items<SyncFailureView> failures(@PathVariable long runId) {
        if (queryService.find(runId).isEmpty()) {
            throw new SyncRunNotFoundException();
        }
        return new Items<>(queryService.failures(runId));
    }

    /** 없는 runId → 404 SYNC_RUN_NOT_FOUND (Problem Details, SYNC-9-E2). */
    @ExceptionHandler(SyncRunNotFoundException.class)
    ProblemDetail notFound() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "실행 기록을 찾을 수 없어요.");
        problem.setProperty("code", "SYNC_RUN_NOT_FOUND");
        return problem;
    }

    static class SyncRunNotFoundException extends RuntimeException {
    }
}
