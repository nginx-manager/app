package net.nginxmanager.site;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * 사이트 유형과 관계없이 켜고 끄는 nginx 모듈 설정: 접근 제한(access, auth_basic), 속도 제한(limit_req, limit_conn),
 * 압축(gzip), 응답 헤더(headers), HTTP/3, 실제 클라이언트 IP(realip), 프록시 캐시, 디렉터리 목록(autoindex), 요청 크기.
 * 값은 nginx 설정에 그대로 들어가므로 형식을 검사한 값만 받는다.
 *
 * @param maxBodySizeMb 요청 본문 최대 크기(MB). null이면 nginx 기본값(1MB), 0이면 제한 없음
 * @param allowFrom 이 주소에서 오는 요청만 받는다(IP나 CIDR, 공백·쉼표·줄바꿈 구분). 비우면 제한 없음
 * @param denyFrom 이 주소에서 오는 요청을 막는다
 * @param authUsers 비밀번호 보호 사용자. 줄마다 "이름" 또는 "이름:새 비밀번호". 이름만 적으면 저장된 비밀번호를 그대로 쓴다
 * @param satisfyAny 허용 주소이거나 비밀번호가 맞으면 통과(둘 다 켰을 때). false면 둘 다 맞아야 통과
 * @param rateLimit 클라이언트 IP당 초당 요청 수. null이면 제한 없음
 * @param rateBurst 순간적으로 더 받아 줄 요청 수
 * @param connLimit 클라이언트 IP당 동시 연결 수. null이면 제한 없음
 * @param realIpFrom 실제 클라이언트 IP를 헤더에서 읽을 때 믿을 프록시 주소(로드 밸런서, CDN)
 * @param cacheMinutes 프록시 응답을 캐시할 시간(분). null이면 캐시하지 않는다. 리버스 프록시에서만 쓴다
 * @param autoindex 정적 사이트에서 index.html이 없는 폴더의 파일 목록을 보여 준다
 * @param authRequestUrl 요청마다 먼저 물어볼 인증 서버 주소(auth_request, 018). 2xx면 통과, 401·403이면 거절.
 *                       oauth2-proxy, Authelia, Authentik 같은 SSO 앞단에 쓴다
 * @param authSignInUrl 인증 서버가 401을 주면 보낼 로그인 주소. 원래 주소를 rd 파라미터로 붙인다. 비우면 401을 그대로 준다
 */
public record SiteOptions(
        Boolean gzip,
        Boolean securityHeaders,
        Boolean hsts,
        Boolean http3,
        @Min(value = 0, message = "{msg.validation.maxBodySize}") @Max(value = 10240, message = "{msg.validation.maxBodySize}")
        Integer maxBodySizeMb,
        String allowFrom,
        String denyFrom,
        String authUsers,
        Boolean satisfyAny,
        @Min(value = 1, message = "{msg.validation.rateLimit}") @Max(value = 100000, message = "{msg.validation.rateLimit}")
        Integer rateLimit,
        @Min(value = 0, message = "{msg.validation.rateBurst}") @Max(value = 100000, message = "{msg.validation.rateBurst}")
        Integer rateBurst,
        @Min(value = 1, message = "{msg.validation.connLimit}") @Max(value = 100000, message = "{msg.validation.connLimit}")
        Integer connLimit,
        String realIpFrom,
        RealIpHeader realIpHeader,
        @Min(value = 1, message = "{msg.validation.cacheMinutes}") @Max(value = 525600, message = "{msg.validation.cacheMinutes}")
        Integer cacheMinutes,
        Boolean autoindex,
        String authRequestUrl,
        String authSignInUrl) {

    public static final SiteOptions DEFAULTS = new SiteOptions(false, false, false, false, null, null, null, null,
            false, null, null, null, null, null, null, false, null, null);

    /** 인증 서버 주소. nginx 변수($)·따옴표·공백이 들어가지 않게 쓸 수 있는 문자를 좁힌다. */
    private static final Pattern AUTH_REQUEST_URL = Pattern.compile(
            "https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[A-Za-z0-9._~/%!&*+,=:@-]*)?(\\?[A-Za-z0-9._~/%!&*+,=:@?-]*)?");

    /** 비밀번호 보호 사용자 이름. htpasswd 형식(이름:해시)이라 콜론과 공백은 쓸 수 없다. */
    static final Pattern AUTH_USER = Pattern.compile("[A-Za-z0-9._@-]{1,64}");
    static final int MIN_PASSWORD = 8;
    static final int MAX_AUTH_USERS = 100;
    static final int MAX_ADDRESSES = 200;

    private static final Pattern IPV4 = Pattern.compile(
            "((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(/(3[0-2]|[12]?\\d))?");
    private static final Pattern IPV6_PREFIX = Pattern.compile("12[0-8]|1[01]\\d|[1-9]?\\d");
    private static final Pattern IPV6_GROUP = Pattern.compile("[0-9A-Fa-f]{1,4}");
    private static final Pattern IPV4_PLAIN = Pattern.compile(
            "((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");

    /** 체크박스 값이 없으면 끈 것으로 본다. */
    public SiteOptions {
        gzip = gzip != null && gzip;
        securityHeaders = securityHeaders != null && securityHeaders;
        hsts = hsts != null && hsts;
        http3 = http3 != null && http3;
        satisfyAny = satisfyAny != null && satisfyAny;
        autoindex = autoindex != null && autoindex;
        realIpHeader = realIpHeader == null ? RealIpHeader.X_FORWARDED_FOR : realIpHeader;
        authRequestUrl = authRequestUrl == null || authRequestUrl.isBlank() ? null : authRequestUrl.strip();
        authSignInUrl = authRequestUrl == null || authSignInUrl == null || authSignInUrl.isBlank()
                ? null : authSignInUrl.strip();
    }

    /** 외부 인증 서버(auth_request)를 쓰는지(018). */
    public boolean authRequestEnabled() {
        return authRequestUrl != null;
    }

    @AssertTrue(message = "{msg.validation.authRequestUrl}")
    public boolean isAuthRequestUrlValid() {
        return authRequestUrl == null
                || (authRequestUrl.length() <= 2048 && AUTH_REQUEST_URL.matcher(authRequestUrl).matches());
    }

    @AssertTrue(message = "{msg.validation.authSignInUrl}")
    public boolean isAuthSignInUrlValid() {
        return authSignInUrl == null
                || (authSignInUrl.length() <= 2048 && AUTH_REQUEST_URL.matcher(authSignInUrl).matches());
    }

    public List<String> allowList() {
        return addresses(allowFrom);
    }

    public List<String> denyList() {
        return addresses(denyFrom);
    }

    public List<String> realIpList() {
        return addresses(realIpFrom);
    }

    /** 비밀번호 보호 사용자: 이름 → 새 비밀번호(이름만 적었으면 null). 적은 순서를 지킨다. */
    public Map<String, String> authEntries() {
        Map<String, String> entries = new LinkedHashMap<>();
        if (authUsers == null) {
            return entries;
        }
        for (String line : authUsers.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            String name = colon < 0 ? trimmed : trimmed.substring(0, colon).strip();
            String password = colon < 0 ? null : trimmed.substring(colon + 1);
            entries.put(name, password == null || password.isEmpty() ? null : password);
        }
        return entries;
    }

    public boolean authEnabled() {
        return !authEntries().isEmpty();
    }

    public boolean accessRestricted() {
        return !allowList().isEmpty() || !denyList().isEmpty();
    }

    /** 사용자 이름만 남긴 값(저장·화면 표시용). 비밀번호는 htpasswd 파일에만 해시로 남는다. */
    public SiteOptions withoutPasswords() {
        List<String> names = new ArrayList<>(authEntries().keySet());
        return new SiteOptions(gzip, securityHeaders, hsts, http3, maxBodySizeMb, normalized(allowFrom),
                normalized(denyFrom), names.isEmpty() ? null : String.join("\n", names), satisfyAny, rateLimit,
                rateLimit == null ? null : rateBurst, connLimit, normalized(realIpFrom), realIpHeader, cacheMinutes,
                autoindex, authRequestUrl, authSignInUrl);
    }

    /** 사이트 유형에서 쓰지 않는 설정은 버린다(유형을 바꿨을 때 남은 값이 설정에 섞이지 않게). */
    SiteOptions forType(SiteType type) {
        return new SiteOptions(gzip, securityHeaders, hsts, http3, maxBodySizeMb, allowFrom, denyFrom, authUsers,
                satisfyAny, rateLimit, rateBurst, connLimit, realIpFrom, realIpHeader,
                type == SiteType.PROXY ? cacheMinutes : null, type == SiteType.STATIC && autoindex, authRequestUrl,
                authSignInUrl);
    }

    @AssertTrue(message = "{msg.validation.addresses}")
    public boolean isAllowFromValid() {
        return validAddresses(allowFrom);
    }

    @AssertTrue(message = "{msg.validation.addresses}")
    public boolean isDenyFromValid() {
        return validAddresses(denyFrom);
    }

    @AssertTrue(message = "{msg.validation.addresses}")
    public boolean isRealIpFromValid() {
        return validAddresses(realIpFrom);
    }

    @AssertTrue(message = "{msg.validation.authUsers}")
    public boolean isAuthUsersValid() {
        if (authUsers == null) {
            return true;
        }
        Map<String, String> entries = authEntries();
        if (entries.size() > MAX_AUTH_USERS) {
            return false;
        }
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            if (!AUTH_USER.matcher(entry.getKey()).matches()) {
                return false;
            }
            String password = entry.getValue();
            if (password != null && (password.length() < MIN_PASSWORD || password.length() > 128)) {
                return false;
            }
        }
        return true;
    }

    private static boolean validAddresses(String value) {
        List<String> list = addresses(value);
        return list.size() <= MAX_ADDRESSES && list.stream().allMatch(SiteOptions::isAddress);
    }

    static boolean isAddress(String value) {
        return IPV4.matcher(value).matches() || isIpv6(value);
    }

    /** nginx가 받는 IPv6 주소(또는 CIDR)인지 본다. "::"는 한 번만, 16비트 묶음은 8개(생략하면 7개 이하)다. */
    static boolean isIpv6(String value) {
        int slash = value.indexOf('/');
        String address = slash < 0 ? value : value.substring(0, slash);
        if (slash >= 0 && !IPV6_PREFIX.matcher(value.substring(slash + 1)).matches()) {
            return false;
        }
        int compressed = address.indexOf("::");
        if (!address.contains(":") || (compressed >= 0 && address.indexOf("::", compressed + 1) >= 0)) {
            return false;
        }
        String[] halves = compressed < 0 ? new String[] {address} : new String[] {
                address.substring(0, compressed), address.substring(compressed + 2)};
        int groups = 0;
        for (int h = 0; h < halves.length; h++) {
            if (halves[h].isEmpty()) {
                continue;
            }
            String[] parts = halves[h].split(":", -1);
            for (int i = 0; i < parts.length; i++) {
                boolean last = h == halves.length - 1 && i == parts.length - 1;
                if (last && parts[i].contains(".")) {
                    // ::ffff:10.0.0.1 처럼 끝의 IPv4는 묶음 두 개다
                    if (!IPV4_PLAIN.matcher(parts[i]).matches()) {
                        return false;
                    }
                    groups += 2;
                } else if (IPV6_GROUP.matcher(parts[i]).matches()) {
                    groups++;
                } else {
                    return false;
                }
            }
        }
        return compressed < 0 ? groups == 8 : groups <= 7;
    }

    static List<String> addresses(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.trim().split("[\\s,]+")).distinct().toList();
    }

    private static String normalized(String value) {
        List<String> list = addresses(value);
        return list.isEmpty() ? null : String.join("\n", list);
    }

    /** 저장용. 비밀번호 없이 사용자 이름만 담긴 값을 넘긴다. */
    void store(Properties values, String prefix) {
        put(values, prefix + "gzip", gzip);
        put(values, prefix + "securityHeaders", securityHeaders);
        put(values, prefix + "hsts", hsts);
        put(values, prefix + "http3", http3);
        put(values, prefix + "maxBodySizeMb", maxBodySizeMb);
        put(values, prefix + "allowFrom", allowFrom);
        put(values, prefix + "denyFrom", denyFrom);
        put(values, prefix + "authUsers", authUsers);
        put(values, prefix + "satisfyAny", satisfyAny);
        put(values, prefix + "rateLimit", rateLimit);
        put(values, prefix + "rateBurst", rateBurst);
        put(values, prefix + "connLimit", connLimit);
        put(values, prefix + "realIpFrom", realIpFrom);
        put(values, prefix + "realIpHeader", realIpHeader.name());
        put(values, prefix + "cacheMinutes", cacheMinutes);
        put(values, prefix + "autoindex", autoindex);
        put(values, prefix + "authRequestUrl", authRequestUrl);
        put(values, prefix + "authSignInUrl", authSignInUrl);
    }

    static SiteOptions load(Properties values, String prefix) {
        String header = values.getProperty(prefix + "realIpHeader");
        return new SiteOptions(bool(values, prefix + "gzip"), bool(values, prefix + "securityHeaders"),
                bool(values, prefix + "hsts"), bool(values, prefix + "http3"),
                integer(values, prefix + "maxBodySizeMb"), values.getProperty(prefix + "allowFrom"),
                values.getProperty(prefix + "denyFrom"), values.getProperty(prefix + "authUsers"),
                bool(values, prefix + "satisfyAny"), integer(values, prefix + "rateLimit"),
                integer(values, prefix + "rateBurst"), integer(values, prefix + "connLimit"),
                values.getProperty(prefix + "realIpFrom"), header == null ? null : RealIpHeader.valueOf(header),
                integer(values, prefix + "cacheMinutes"), bool(values, prefix + "autoindex"),
                values.getProperty(prefix + "authRequestUrl"), values.getProperty(prefix + "authSignInUrl"));
    }

    private static void put(Properties values, String key, Object value) {
        if (value != null) {
            values.setProperty(key, value.toString());
        }
    }

    private static Boolean bool(Properties values, String key) {
        return Boolean.parseBoolean(values.getProperty(key));
    }

    private static Integer integer(Properties values, String key) {
        String value = values.getProperty(key);
        return value == null ? null : Integer.valueOf(value);
    }

    /** 실제 클라이언트 IP가 담긴 헤더. */
    public enum RealIpHeader {
        X_FORWARDED_FOR("X-Forwarded-For"),
        X_REAL_IP("X-Real-IP"),
        CF_CONNECTING_IP("CF-Connecting-IP");

        private final String header;

        RealIpHeader(String header) {
            this.header = header;
        }

        public String header() {
            return header;
        }
    }
}
