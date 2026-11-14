package io.github.cheatiskey.subsidy.ingestion;

/** serviceList 한 페이지의 응답 본문(가공 전 문자열)을 돌려준다. */
public interface ServiceListSource {

    String fetchPage(int page, int perPage);
}
