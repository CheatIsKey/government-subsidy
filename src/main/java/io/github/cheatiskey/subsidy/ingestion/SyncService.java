package io.github.cheatiskey.subsidy.ingestion;

import java.time.Clock;
import org.springframework.stereotype.Service;

// 껍데기(2단계 테스트 컴파일용). 로직 없음. 3단계에서 구현한다.
@Service
public class SyncService {

    public SyncService(ServiceListSource source, Clock clock) {
    }

    /** 전체(FULL) 동기화를 한 번 실행하고, 끝난 실행 기록을 돌려준다. */
    public SyncRunView runFull() {
        throw new UnsupportedOperationException("not implemented");
    }
}
