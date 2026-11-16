package io.github.cheatiskey.subsidy.ingestion;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** 공공 API 실패를 Gov24ApiException으로 바꾼다. 외부에서 온 글은 서비스 키를 가린다. */
public final class Gov24Errors {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Gov24Errors() {
    }

    /** 4xx/5xx 응답. 본문에서는 공공 API code만 꺼낸다. */
    public static Gov24ApiException httpError(int status, String path, int page, String responseBody, String serviceKey) {
        String apiCode = ServiceKeyMasker.mask(extractCode(responseBody), serviceKey);
        String message = Gov24ApiException.Kind.HTTP_ERROR + " status=" + status + " path=" + path + " page=" + page
                + (apiCode == null ? "" : " code=" + apiCode);
        return new Gov24ApiException(Gov24ApiException.Kind.HTTP_ERROR, status, path, page, apiCode, message);
    }

    /** 응답을 받지 못했거나 읽다가 실패. cause 종류로 TIMEOUT / CONNECTION / IO를 정한다. */
    public static Gov24ApiException ioError(Throwable cause, String path, int page, String serviceKey) {
        Gov24ApiException.Kind kind = kindOf(cause);
        String message = kind + " path=" + path + " page=" + page + " cause=" + cause.getClass().getName();
        return new Gov24ApiException(kind, null, path, page, null, message);
    }

    // 껍데기(PR #8 2단계 테스트 컴파일용, OPS-5-E18·E20). 로직 없음. 3단계에서 구현한다.
    /** HTTP 상태를 받은 뒤의 실패(오류 본문 읽기 실패, 해석할 수 없는 Content-Type). 메시지에 status를 붙인다. */
    public static Gov24ApiException ioErrorAfterStatus(Throwable cause, int status, String path, int page, String serviceKey) {
        throw new UnsupportedOperationException("OPS-5-E18·E20 미구현");
    }

    /** 좁은 타입부터 검사한다: HttpConnectTimeoutException은 HttpTimeoutException의 자식이다. */
    private static Gov24ApiException.Kind kindOf(Throwable cause) {
        if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException) {
            return Gov24ApiException.Kind.CONNECTION;
        }
        if (cause instanceof HttpTimeoutException) {
            return Gov24ApiException.Kind.TIMEOUT;
        }
        return Gov24ApiException.Kind.IO;
    }

    private static String extractCode(String body) {
        if (body == null) {
            return null;
        }
        try {
            Object parsed = JSON.readValue(body, Object.class);
            if (parsed instanceof Map<?, ?> map && map.get("code") != null) {
                return String.valueOf(map.get("code"));
            }
        } catch (JacksonException e) {
            // JSON이 아니면 code 없음
        }
        return null;
    }
}
