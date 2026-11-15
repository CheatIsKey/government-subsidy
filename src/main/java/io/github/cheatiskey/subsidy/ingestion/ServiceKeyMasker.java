package io.github.cheatiskey.subsidy.ingestion;

// 껍데기(이슈 #4 2단계 테스트 컴파일용). 로직 없음. 3단계에서 구현한다.
/** 글 안의 서비스 키 네 형태(원래·대문자 인코딩·소문자 인코딩·부분 인코딩)를 **** 로 가린다. */
public final class ServiceKeyMasker {

    private ServiceKeyMasker() {
    }

    public static String mask(String text, String serviceKey) {
        throw new UnsupportedOperationException("not implemented");
    }
}
