package io.github.cheatiskey.subsidy.ingestion;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

// 껍데기(2단계 테스트 컴파일용). 로직 없음. 3단계에서 구현한다.
@Service
public class SyncRunQueryService {

    public Optional<SyncRunView> find(long runId) {
        throw new UnsupportedOperationException("not implemented");
    }

    public List<SyncRunView> list(int size) {
        throw new UnsupportedOperationException("not implemented");
    }

    public List<SyncFailureView> failures(long runId) {
        throw new UnsupportedOperationException("not implemented");
    }
}
