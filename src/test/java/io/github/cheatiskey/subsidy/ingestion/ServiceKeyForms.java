package io.github.cheatiskey.subsidy.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

/**
 * 테스트용 서비스 키와 "키 없음" 확인 (docs/cases.md OPS-5-E1 세부 머리말).
 * 키 없음 = 아래 네 형태가 하나도 나오지 않음.
 */
final class ServiceKeyForms {

    static final String KEY = "ab+c/d==";
    static final String UPPER_ENCODED = "ab%2Bc%2Fd%3D%3D";
    static final String LOWER_ENCODED = "ab%2bc%2fd%3d%3d";
    static final String PARTIAL_ENCODED = "ab%2Bc/d==";
    static final List<String> ALL = List.of(KEY, UPPER_ENCODED, LOWER_ENCODED, PARTIAL_ENCODED);

    private ServiceKeyForms() {
    }

    static void assertNoKey(String text) {
        assertThat(text).as("서비스 키 네 형태가 없어야 함").doesNotContain(ALL);
    }

    /** 긴 글(콘솔 전체 등)용. 실패하면 키가 처음 나온 곳 앞뒤를 실패 메시지에 붙인다. */
    static void assertNoKeyInLongText(String text) {
        for (String form : ALL) {
            int at = text.indexOf(form);
            if (at >= 0) {
                String around = text.substring(Math.max(0, at - 300), Math.min(text.length(), at + 100));
                throw new AssertionError("서비스 키(" + form + ")가 출력에 있음. 앞뒤 출력:%n%s".formatted(around));
            }
        }
    }

    /** "Caused by"까지 포함한 스택 트레이스 전체. */
    static String stackTrace(Throwable t) {
        StringWriter out = new StringWriter();
        t.printStackTrace(new PrintWriter(out));
        return out.toString();
    }
}
