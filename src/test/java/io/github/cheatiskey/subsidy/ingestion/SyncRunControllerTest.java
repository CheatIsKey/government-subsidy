package io.github.cheatiskey.subsidy.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** SYNC-9 운영 API (단위: 웹 계층만). 운영 API는 local 프로필에서만 켜진다 (Q-17). */
@WebMvcTest
@ActiveProfiles("local")
class SyncRunControllerTest {

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    SyncRunQueryService syncRunQueryService;

    @Test
    @DisplayName("SYNC-9-E2 없는 runId를 조회하면 404 SYNC_RUN_NOT_FOUND (Problem Details)")
    void e2_unknownRunId() {
        given(syncRunQueryService.find(999L)).willReturn(Optional.empty());

        assertThat(mvc.get().uri("/ops/v1/sync-runs/{runId}", 999))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .hasPathSatisfying("$.status", v -> v.assertThat().isEqualTo(404))
                .hasPathSatisfying("$.code", v -> v.assertThat().isEqualTo("SYNC_RUN_NOT_FOUND"));
    }
}
