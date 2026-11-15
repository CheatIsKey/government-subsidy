package io.github.cheatiskey.subsidy.ingestion;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.util.UriUtils;

/** 글 안의 서비스 키 네 형태(원래·대문자 인코딩·소문자 인코딩·부분 인코딩)를 **** 로 가린다. */
public final class ServiceKeyMasker {

    private static final String MASK = "****";
    private static final Pattern PERCENT_ESCAPE = Pattern.compile("%[0-9A-F]{2}");

    private ServiceKeyMasker() {
    }

    public static String mask(String text, String serviceKey) {
        if (text == null || serviceKey == null || serviceKey.isEmpty()) {
            return text;
        }
        String upper = UriUtils.encode(serviceKey, StandardCharsets.UTF_8);
        String lower = PERCENT_ESCAPE.matcher(upper).replaceAll(m -> Matcher.quoteReplacement(m.group().toLowerCase()));
        String partial = serviceKey.replace("+", "%2B");
        String masked = text;
        for (String form : new String[] {serviceKey, upper, lower, partial}) {
            masked = masked.replace(form, MASK);
        }
        return masked;
    }
}
