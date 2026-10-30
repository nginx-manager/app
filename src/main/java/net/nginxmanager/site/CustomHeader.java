package net.nginxmanager.site;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 사용자 지정 헤더 한 줄(022): "이름: 값". 값은 nginx 문자열("…")로 감싸 넣으므로 따옴표를 깨는 문자만 막는다.
 * $변수는 nginx 변수로 쓴다.
 *
 * @param name  헤더 이름
 * @param value 헤더 값. 비어 있을 수 있다(백엔드 헤더면 그 헤더를 지운다)
 */
public record CustomHeader(String name, String value) {

    static final int MAX_HEADERS = 20;
    static final int MAX_VALUE = 500;

    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    /** nginx 문자열을 깨는 큰따옴표·역슬래시와 제어 문자. */
    private static final Pattern FORBIDDEN = Pattern.compile("[\"\\\\\\p{Cntrl}]");
    /** 관리도구가 정하는 백엔드 헤더. Host는 덮어쓸 수 있다. */
    private static final Set<String> MANAGED_REQUEST = Set.of("x-real-ip", "x-forwarded-for", "x-forwarded-proto",
            "upgrade", "connection", "x-auth-request-user", "x-auth-request-email");

    /** nginx 설정에 넣을 값. 따옴표로 감싼다. */
    String quotedValue() {
        return "\"" + value + "\"";
    }

    boolean isHost() {
        return name.equalsIgnoreCase("Host");
    }

    /** 줄마다 "이름: 값". 빈 줄은 건너뛰고, 콜론이 없는 줄은 그대로 남겨 검증에서 걸리게 한다. */
    static List<CustomHeader> parse(String value) {
        List<CustomHeader> headers = new ArrayList<>();
        if (value == null) {
            return headers;
        }
        for (String line : value.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            headers.add(colon < 0 ? new CustomHeader(trimmed, null)
                    : new CustomHeader(trimmed.substring(0, colon).strip(), trimmed.substring(colon + 1).strip()));
        }
        return headers;
    }

    static String normalized(String value) {
        List<CustomHeader> headers = parse(value);
        return headers.isEmpty() ? null : String.join("\n", headers.stream()
                .map(h -> h.value() == null ? h.name() : h.value().isEmpty() ? h.name() + ":" : h.name() + ": " + h.value())
                .toList());
    }

    /** @param request 백엔드로 보낼 헤더면 true. 관리도구가 정하는 헤더를 막는다 */
    static boolean valid(String value, boolean request) {
        List<CustomHeader> headers = parse(value);
        if (headers.size() > MAX_HEADERS) {
            return false;
        }
        Set<String> names = new HashSet<>();
        for (CustomHeader header : headers) {
            if (header.value() == null || !NAME.matcher(header.name()).matches()
                    || header.value().length() > MAX_VALUE || FORBIDDEN.matcher(header.value()).find()
                    || header.value().endsWith("$")) {
                return false;
            }
            String lower = header.name().toLowerCase(Locale.ROOT);
            if (!names.add(lower) || (request && MANAGED_REQUEST.contains(lower))) {
                return false;
            }
        }
        return true;
    }
}
