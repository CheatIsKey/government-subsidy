package io.github.cheatiskey.subsidy.ingestion;

// 껍데기(이슈 #4 2단계 테스트 컴파일용). 로직 없음. 3단계에서 구현한다.
/** 공공 API 실패를 Gov24ApiException으로 바꾼다. 외부에서 온 글은 서비스 키를 가린다. */
public final class Gov24Errors {

    private Gov24Errors() {
    }

    /** 4xx/5xx 응답. 본문에서는 공공 API code만 꺼낸다. */
    public static Gov24ApiException httpError(int status, String path, int page, String responseBody, String serviceKey) {
        throw new UnsupportedOperationException("not implemented");
    }

    /** 응답을 받지 못했거나 읽다가 실패. cause 종류로 TIMEOUT / CONNECTION / IO를 정한다. */
    public static Gov24ApiException ioError(Throwable cause, String path, int page, String serviceKey) {
        throw new UnsupportedOperationException("not implemented");
    }
}
