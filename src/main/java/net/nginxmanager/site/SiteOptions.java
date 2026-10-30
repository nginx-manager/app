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
 * @param pathRoutes 경로별 라우팅(019). 줄마다 "경로 대상". 대상은 프록시 주소나 디렉터리. 리버스 프록시·정적 사이트에서만 쓴다
 * @param backupTargets 주 업스트림이 모두 실패할 때만 받는 백업 업스트림(020, upstream의 backup). 리버스 프록시에서만 쓴다
 * @param upstreamMaxFails 이 횟수만큼 실패하면 업스트림을 잠시 뺀다(max_fails). null이면 nginx 기본값 1
 * @param upstreamFailTimeout 실패를 세는 시간이자 빼 두는 시간(초, fail_timeout). null이면 nginx 기본값 10초
 * @param upstreamKeepalive 업스트림 연결을 재사용할 유휴 연결 수(keepalive). null이면 요청마다 새로 연결한다
 * @param maintenance 점검 모드(021). 사이트 내용 요청에 503과 안내 페이지를 준다
 * @param maintenanceMessage 점검 안내 문구. 비우면 기본 문구
 * @param maintenanceAllowFrom 점검 중에도 평소처럼 들어올 수 있는 주소(작업자)
 * @param responseHeaders 응답에 붙일 헤더(022). 줄마다 "이름: 값"
 * @param requestHeaders 백엔드로 보낼 헤더(022). 줄마다 "이름: 값". 리버스 프록시에서만 쓴다
 * @param redirectToPrimary 추가 도메인으로 온 요청을 대표 도메인으로 301 보낸다(023)
 * @param backendErrorPage nginx가 백엔드 장애로 502·504를 낼 때 안내 페이지를 보여 준다(026). 리버스 프록시·PHP에서만 쓴다
 * @param backendErrorMessage 백엔드 장애 안내 문구. 비우면 기본 문구
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
        String authSignInUrl,
        String pathRoutes,
        String backupTargets,
        @Min(value = 1, message = "{msg.validation.upstreamMaxFails}") @Max(value = 100, message = "{msg.validation.upstreamMaxFails}")
        Integer upstreamMaxFails,
        @Min(value = 1, message = "{msg.validation.upstreamFailTimeout}") @Max(value = 3600, message = "{msg.validation.upstreamFailTimeout}")
        Integer upstreamFailTimeout,
        @Min(value = 1, message = "{msg.validation.upstreamKeepalive}") @Max(value = 1024, message = "{msg.validation.upstreamKeepalive}")
        Integer upstreamKeepalive,
        Boolean maintenance,
        String maintenanceMessage,
        String maintenanceAllowFrom,
        String responseHeaders,
        String requestHeaders,
        Boolean redirectToPrimary,
        Boolean backendErrorPage,
        String backendErrorMessage,
        String note,
        String serverDirectives) {

    public static final SiteOptions DEFAULTS = new SiteOptions(false, false, false, false, null, null, null, null,
            false, null, null, null, null, null, null, false, null, null, null, null, null, null, null, false, null,
            null, null, null, false, false, null, null, null);

    /** 점검 안내 문구에 쓸 수 없는 문자: nginx 문자열('…')과 변수를 깨뜨린다. */
    private static final Pattern MAINTENANCE_FORBIDDEN = Pattern.compile("['\\\\${}]");
    static final int MAX_MAINTENANCE_MESSAGE = 500;

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
        pathRoutes = pathRoutes == null || pathRoutes.isBlank() ? null : pathRoutes;
        backupTargets = backupTargets == null || backupTargets.isBlank() ? null : backupTargets;
        maintenance = maintenance != null && maintenance;
        maintenanceMessage = maintenanceMessage == null || maintenanceMessage.isBlank() ? null : maintenanceMessage.strip();
        responseHeaders = responseHeaders == null || responseHeaders.isBlank() ? null : responseHeaders;
        requestHeaders = requestHeaders == null || requestHeaders.isBlank() ? null : requestHeaders;
        redirectToPrimary = redirectToPrimary != null && redirectToPrimary;
        backendErrorPage = backendErrorPage != null && backendErrorPage;
        backendErrorMessage = backendErrorMessage == null || backendErrorMessage.isBlank() ? null : backendErrorMessage.strip();
        note = note == null || note.isBlank() ? null : note.replace("\r\n", "\n").strip();
        serverDirectives = serverDirectives == null || serverDirectives.isBlank() ? null
                : serverDirectives.replace("\r\n", "\n").stripTrailing();
    }

    public List<CustomHeader> responseHeaderList() {
        return CustomHeader.parse(responseHeaders);
    }

    public List<CustomHeader> requestHeaderList() {
        return CustomHeader.parse(requestHeaders);
    }

    @AssertTrue(message = "{msg.validation.headers}")
    public boolean isResponseHeadersValid() {
        return CustomHeader.valid(responseHeaders, false);
    }

    @AssertTrue(message = "{msg.validation.requestHeaders}")
    public boolean isRequestHeadersValid() {
        return CustomHeader.valid(requestHeaders, true);
    }

    public List<String> maintenanceAllowList() {
        return addresses(maintenanceAllowFrom);
    }

    @AssertTrue(message = "{msg.validation.addresses}")
    public boolean isMaintenanceAllowFromValid() {
        return validAddresses(maintenanceAllowFrom);
    }

    @AssertTrue(message = "{msg.validation.maintenanceMessage}")
    public boolean isMaintenanceMessageValid() {
        return validNotice(maintenanceMessage);
    }

    @AssertTrue(message = "{msg.validation.backendErrorMessage}")
    public boolean isBackendErrorMessageValid() {
        return validNotice(backendErrorMessage);
    }

    /** 안내 페이지 문구(점검 모드, 백엔드 장애): nginx 문자열('…')에 넣으므로 그 문자열을 깨는 문자를 막는다. */
    static final int MAX_NOTE = 500;
    static final int MAX_SERVER_DIRECTIVES = 4000;
    /** 줄바꿈·탭 말고는 제어 문자를 받지 않는다. */
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");

    @AssertTrue(message = "{msg.validation.note}")
    public boolean isNoteValid() {
        return note == null || (note.length() <= MAX_NOTE && !CONTROL.matcher(note).find());
    }

    /**
     * 추가 지시어(032)는 nginx -t가 문법을 본다. 여기서는 server 블록 밖으로 새지 않도록 중괄호와 따옴표 짝만 본다.
     * 따옴표 안의 중괄호와 주석(#부터 줄 끝)은 세지 않는다.
     */
    @AssertTrue(message = "{msg.validation.serverDirectives}")
    public boolean isServerDirectivesValid() {
        if (serverDirectives == null) {
            return true;
        }
        if (serverDirectives.length() > MAX_SERVER_DIRECTIVES || CONTROL.matcher(serverDirectives).find()) {
            return false;
        }
        int depth = 0;
        char quote = 0;
        boolean comment = false;
        for (int i = 0; i < serverDirectives.length(); i++) {
            char c = serverDirectives.charAt(i);
            if (comment) {
                comment = c != '\n';
            } else if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '#') {
                comment = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth < 0) {
                return false;
            }
        }
        return depth == 0 && quote == 0;
    }

    /** 메모를 설정 파일 주석으로(031). */
    public List<String> noteLines() {
        return note == null ? List.of() : List.of(note.split("\n"));
    }

    private static boolean validNotice(String message) {
        return message == null || (message.length() <= MAX_MAINTENANCE_MESSAGE
                && !MAINTENANCE_FORBIDDEN.matcher(message).find());
    }

    /** 백업 업스트림 주소 목록(020, 중복 제거, 순서 유지). */
    public List<String> backupTargetList() {
        return addresses(backupTargets);
    }

    /** upstream 블록이 있어야 쓸 수 있는 설정(백업, 장애 판정, keepalive)을 하나라도 쓰는지(020). */
    public boolean upstreamTuned() {
        return backupTargets != null || upstreamMaxFails != null || upstreamFailTimeout != null
                || upstreamKeepalive != null;
    }

    /** 경로별 라우팅(019). 적은 순서대로. */
    public List<PathRoute> pathRouteList() {
        return PathRoute.parse(pathRoutes);
    }

    @AssertTrue(message = "{msg.validation.pathRoutes}")
    public boolean isPathRoutesValid() {
        return PathRoute.valid(pathRoutes);
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
                autoindex, authRequestUrl, authSignInUrl, PathRoute.normalized(pathRoutes), normalized(backupTargets),
                upstreamMaxFails, upstreamFailTimeout, upstreamKeepalive, maintenance, maintenanceMessage,
                normalized(maintenanceAllowFrom), CustomHeader.normalized(responseHeaders),
                CustomHeader.normalized(requestHeaders), redirectToPrimary, backendErrorPage, backendErrorMessage,
                note, serverDirectives);
    }

    /** 비밀번호 보호 사용자를 뺀 값(024, 사이트 복제). 비밀번호는 해시로만 남아 옮길 수 없다. */
    SiteOptions withoutAuthUsers() {
        Properties values = new Properties();
        store(values, "");
        values.remove("authUsers");
        return load(values, "");
    }

    /** 점검 모드만 바꾼 값(033, API). */
    SiteOptions withMaintenance(boolean on) {
        return new SiteOptions(gzip, securityHeaders, hsts, http3, maxBodySizeMb, allowFrom, denyFrom, authUsers,
                satisfyAny, rateLimit, rateBurst, connLimit, realIpFrom, realIpHeader, cacheMinutes, autoindex,
                authRequestUrl, authSignInUrl, pathRoutes, backupTargets, upstreamMaxFails, upstreamFailTimeout,
                upstreamKeepalive, on, maintenanceMessage, maintenanceAllowFrom, responseHeaders,
                requestHeaders, redirectToPrimary, backendErrorPage, backendErrorMessage, note, serverDirectives);
    }

    /** 추가 지시어만 바꾼 값(032). 멤버가 보낸 값은 이것으로 기존 값으로 되돌린다. */
    SiteOptions withServerDirectives(String value) {
        return new SiteOptions(gzip, securityHeaders, hsts, http3, maxBodySizeMb, allowFrom, denyFrom, authUsers,
                satisfyAny, rateLimit, rateBurst, connLimit, realIpFrom, realIpHeader, cacheMinutes, autoindex,
                authRequestUrl, authSignInUrl, pathRoutes, backupTargets, upstreamMaxFails, upstreamFailTimeout,
                upstreamKeepalive, maintenance, maintenanceMessage, maintenanceAllowFrom, responseHeaders,
                requestHeaders, redirectToPrimary, backendErrorPage, backendErrorMessage, note, value);
    }

    /** 사이트 유형에서 쓰지 않는 설정은 버린다(유형을 바꿨을 때 남은 값이 설정에 섞이지 않게). */
    SiteOptions forType(SiteType type) {
        return new SiteOptions(gzip, securityHeaders, hsts, http3, maxBodySizeMb, allowFrom, denyFrom, authUsers,
                satisfyAny, rateLimit, rateBurst, connLimit, realIpFrom, realIpHeader,
                type == SiteType.PROXY ? cacheMinutes : null, type == SiteType.STATIC && autoindex, authRequestUrl,
                authSignInUrl, type == SiteType.PROXY || type == SiteType.STATIC ? pathRoutes : null,
                type == SiteType.PROXY ? backupTargets : null, type == SiteType.PROXY ? upstreamMaxFails : null,
                type == SiteType.PROXY ? upstreamFailTimeout : null, type == SiteType.PROXY ? upstreamKeepalive : null,
                maintenance, maintenanceMessage, maintenanceAllowFrom, responseHeaders,
                type == SiteType.PROXY ? requestHeaders : null, redirectToPrimary,
                (type == SiteType.PROXY || type == SiteType.PHP) && backendErrorPage,
                type == SiteType.PROXY || type == SiteType.PHP ? backendErrorMessage : null, note, serverDirectives);
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

    public static boolean isAddress(String value) {
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

    public static List<String> addresses(String value) {
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
        put(values, prefix + "pathRoutes", pathRoutes);
        put(values, prefix + "backupTargets", backupTargets);
        put(values, prefix + "upstreamMaxFails", upstreamMaxFails);
        put(values, prefix + "upstreamFailTimeout", upstreamFailTimeout);
        put(values, prefix + "upstreamKeepalive", upstreamKeepalive);
        put(values, prefix + "maintenance", maintenance);
        put(values, prefix + "maintenanceMessage", maintenanceMessage);
        put(values, prefix + "maintenanceAllowFrom", maintenanceAllowFrom);
        put(values, prefix + "responseHeaders", responseHeaders);
        put(values, prefix + "requestHeaders", requestHeaders);
        put(values, prefix + "redirectToPrimary", redirectToPrimary);
        put(values, prefix + "backendErrorPage", backendErrorPage);
        put(values, prefix + "backendErrorMessage", backendErrorMessage);
        put(values, prefix + "note", note);
        put(values, prefix + "serverDirectives", serverDirectives);
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
                values.getProperty(prefix + "authRequestUrl"), values.getProperty(prefix + "authSignInUrl"),
                values.getProperty(prefix + "pathRoutes"), values.getProperty(prefix + "backupTargets"),
                integer(values, prefix + "upstreamMaxFails"), integer(values, prefix + "upstreamFailTimeout"),
                integer(values, prefix + "upstreamKeepalive"), bool(values, prefix + "maintenance"),
                values.getProperty(prefix + "maintenanceMessage"), values.getProperty(prefix + "maintenanceAllowFrom"),
                values.getProperty(prefix + "responseHeaders"), values.getProperty(prefix + "requestHeaders"),
                bool(values, prefix + "redirectToPrimary"), bool(values, prefix + "backendErrorPage"),
                values.getProperty(prefix + "backendErrorMessage"), values.getProperty(prefix + "note"),
                values.getProperty(prefix + "serverDirectives"));
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
