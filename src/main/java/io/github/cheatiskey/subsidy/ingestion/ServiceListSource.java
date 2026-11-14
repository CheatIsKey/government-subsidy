package io.github.cheatiskey.subsidy.ingestion;

// 껍데기(2단계 테스트 컴파일용). 로직 없음. 3단계에서 구현한다.
/** serviceList 한 페이지의 응답 본문(가공 전 문자열)을 돌려준다. */
public interface ServiceListSource {

    String fetchPage(int page, int perPage);
}
