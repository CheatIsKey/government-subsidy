package io.github.cheatiskey.subsidy.ingestion;

// 껍데기(2단계 테스트 컴파일용). 로직 없음. 3단계에서 구현한다.
public class ServiceListCollector {

    public ServiceListCollector(ServiceListSource source) {
    }

    /**
     * 목록을 처음부터 끝까지 모은다. 페이지 하나를 처리할 때마다 listener에 알린다
     * (실행 기록을 페이지마다 커밋하기 위해, SYNC-9-E1).
     */
    public ListCollectionResult collect(int perPage, PageListener listener) {
        throw new UnsupportedOperationException("not implemented");
    }
}
