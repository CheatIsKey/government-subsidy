package io.github.cheatiskey.subsidy.ingestion;

// 껍데기(이슈 #4 2단계 테스트 컴파일용). 로직 없음. 3단계에서 구현한다.
/**
 * 공공 API 호출 실패를 감싸는 예외 (이슈 #4, docs/decisions.md).
 * 메시지에는 종류·HTTP 상태·경로·page·공공 API code만 넣고, 원래 예외는 cause로 넣지 않는다.
 */
public class Gov24ApiException extends RuntimeException {

    public enum Kind { HTTP_ERROR, TIMEOUT, CONNECTION, IO }

    private final Kind kind;
    private final Integer httpStatus;
    private final String path;
    private final int page;
    private final String apiCode;

    public Gov24ApiException(Kind kind, Integer httpStatus, String path, int page, String apiCode, String message) {
        super(message);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.path = path;
        this.page = page;
        this.apiCode = apiCode;
    }

    public Kind kind() {
        return kind;
    }

    /**
     * HTTP 상태를 받은 경우 값이 있다(4xx/5xx 본문 읽기 실패, 200 + 해석 불가 Content-Type 포함).
     * 200 잘린 본문처럼 응답을 받았어도 status 없이 IO로 끝나면 null.
     */
    public Integer httpStatus() {
        return httpStatus;
    }

    public String path() {
        return path;
    }

    public int page() {
        return page;
    }

    /** 공공 API 오류 본문의 code (예: "-4"). 없으면 null. */
    public String apiCode() {
        return apiCode;
    }
}
