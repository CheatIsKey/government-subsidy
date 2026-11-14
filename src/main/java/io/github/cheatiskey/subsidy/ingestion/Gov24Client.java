package io.github.cheatiskey.subsidy.ingestion;

import org.springframework.stereotype.Component;

// 껍데기(2단계 테스트 컴파일용). 로직 없음. 3단계에서 구현한다.
@Component
public class Gov24Client implements ServiceListSource {

    @Override
    public String fetchPage(int page, int perPage) {
        throw new UnsupportedOperationException("not implemented");
    }
}
