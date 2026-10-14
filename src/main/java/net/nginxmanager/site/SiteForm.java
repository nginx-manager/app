package net.nginxmanager.site;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Locale;

/**
 * 사이트 폼. 값은 nginx 설정에 그대로 들어가므로 세미콜론·중괄호·공백이 들어갈 수 없는 형식만 받는다.
 *
 * @param version 수정할 때 읽어 간 버전. 동시 편집 충돌을 막는다
 * @param proxyTarget 백엔드 주소. 공백·쉼표·줄바꿈으로 여러 개를 적으면 upstream으로 묶는다
 * @param proxyConnectTimeout 백엔드 연결 타임아웃(초). null이면 nginx 기본값(60초)
 * @param proxyReadTimeout 백엔드 응답(읽기·쓰기) 타임아웃(초). null이면 nginx 기본값(60초)
 * @param redirectTarget 리디렉션 사이트가 보낼 주소
 * @param redirectCode 리디렉션 응답 코드(301, 302, 307, 308)
 * @param redirectKeepPath 요청 경로와 쿼리를 새 주소 뒤에 그대로 붙인다
 * @param options 유형과 관계없이 켜고 끄는 모듈 설정(접근 제한, 압축, 헤더 등)
 */
public record SiteForm(
        @Pattern(regexp = SiteForm.DOMAIN, message = "{msg.validation.domain}")
        String primaryDomain,
        String extraDomains,
        @NotNull
        SiteType type,
        @Min(1) @Max(65535)
        int listenHttpPort,
        String staticRoot,
        String proxyTarget,
        Long version,
        Boolean httpsEnabled,
        Boolean redirectHttpToHttps,
        /** 업로드한 인증서. null이면 Let's Encrypt로 자동 발급한다. */
        Long certificateId,
        Boolean proxyWebsocket,
        @Min(value = 1, message = "{msg.validation.timeout}") @Max(value = 3600, message = "{msg.validation.timeout}")
        Integer proxyConnectTimeout,
        @Min(value = 1, message = "{msg.validation.timeout}") @Max(value = 3600, message = "{msg.validation.timeout}")
        Integer proxyReadTimeout,
        LoadBalancing proxyLoadBalancing,
        String redirectTarget,
        Integer redirectCode,
        Boolean redirectKeepPath,
        @Valid
        SiteOptions options) {

    /** 백엔드는 이 수까지 적을 수 있다. */
    static final int MAX_BACKENDS = 32;

    /** 체크박스 값이 아예 없으면(폼 밖의 요청) HTTPS는 끄고, 켰을 때 HTTP는 HTTPS로 넘긴다. */
    public SiteForm {
        httpsEnabled = httpsEnabled != null && httpsEnabled;
        redirectHttpToHttps = redirectHttpToHttps == null || redirectHttpToHttps;
        proxyWebsocket = proxyWebsocket != null && proxyWebsocket;
        proxyLoadBalancing = proxyLoadBalancing == null ? LoadBalancing.ROUND_ROBIN : proxyLoadBalancing;
        redirectCode = redirectCode == null ? 301 : redirectCode;
        redirectKeepPath = redirectKeepPath == null || redirectKeepPath;
        options = options == null ? SiteOptions.DEFAULTS : options;
    }

    /** 프록시 세부 설정까지 정하는 폼(리디렉션·모듈 설정 없음). */
    public SiteForm(String primaryDomain, String extraDomains, SiteType type, int listenHttpPort, String staticRoot,
                    String proxyTarget, Long version, Boolean httpsEnabled, Boolean redirectHttpToHttps,
                    Long certificateId, Boolean proxyWebsocket, Integer proxyConnectTimeout, Integer proxyReadTimeout,
                    LoadBalancing proxyLoadBalancing) {
        this(primaryDomain, extraDomains, type, listenHttpPort, staticRoot, proxyTarget, version, httpsEnabled,
                redirectHttpToHttps, certificateId, proxyWebsocket, proxyConnectTimeout, proxyReadTimeout,
                proxyLoadBalancing, null, null, null, null);
    }

    /** 프록시 세부 설정 없이 HTTPS까지 정하는 폼. */
    public SiteForm(String primaryDomain, String extraDomains, SiteType type, int listenHttpPort, String staticRoot,
                    String proxyTarget, Long version, Boolean httpsEnabled, Boolean redirectHttpToHttps,
                    Long certificateId) {
        this(primaryDomain, extraDomains, type, listenHttpPort, staticRoot, proxyTarget, version, httpsEnabled,
                redirectHttpToHttps, certificateId, false, null, null, null);
    }

    /** HTTPS 없이 쓰던 폼. */
    public SiteForm(String primaryDomain, String extraDomains, SiteType type, int listenHttpPort, String staticRoot,
                    String proxyTarget, Long version) {
        this(primaryDomain, extraDomains, type, listenHttpPort, staticRoot, proxyTarget, version, false, true, null);
    }

    /** 같은 폼에 HTTPS(Let's Encrypt)만 켠다. */
    public SiteForm withLetsEncrypt() {
        return new SiteForm(primaryDomain, extraDomains, type, listenHttpPort, staticRoot, proxyTarget, version, true,
                redirectHttpToHttps, null, proxyWebsocket, proxyConnectTimeout, proxyReadTimeout, proxyLoadBalancing,
                redirectTarget, redirectCode, redirectKeepPath, options);
    }

    /** 같은 폼에 모듈 설정만 바꾼다. */
    public SiteForm withOptions(SiteOptions newOptions) {
        return new SiteForm(primaryDomain, extraDomains, type, listenHttpPort, staticRoot, proxyTarget, version,
                httpsEnabled, redirectHttpToHttps, certificateId, proxyWebsocket, proxyConnectTimeout,
                proxyReadTimeout, proxyLoadBalancing, redirectTarget, redirectCode, redirectKeepPath, newOptions);
    }

    static final String DOMAIN =
            "(?=.{1,253}$)([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)*[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?";
    private static final String EXTRA_DOMAIN = "(\\*\\.)?" + DOMAIN;
    private static final String STATIC_ROOT = "/[A-Za-z0-9._/-]*";
    private static final String PROXY_TARGET = "https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[A-Za-z0-9._~/-]*)?";
    /** 리디렉션 주소. nginx 변수($)·따옴표·공백이 들어가지 않게 쓸 수 있는 문자를 좁힌다. */
    private static final String REDIRECT_TARGET =
            "https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[A-Za-z0-9._~/%!&*+,=:@-]*)?(\\?[A-Za-z0-9._~/%!&*+,=:@?-]*)?";
    public static final List<Integer> REDIRECT_CODES = List.of(301, 302, 307, 308);

    public static SiteForm empty() {
        return new SiteForm("", "", SiteType.PROXY, 80, "", "", null, false, true, null);
    }

    public String extraDomainsNormalized() {
        if (extraDomains == null || extraDomains.isBlank()) {
            return null;
        }
        return Arrays.stream(extraDomains.trim().split("[\\s,]+")).distinct().collect(Collectors.joining(" "));
    }

    @AssertTrue(message = "{msg.validation.extraDomains}")
    public boolean isExtraDomainsValid() {
        String normalized = extraDomainsNormalized();
        return normalized == null || Arrays.stream(normalized.split(" ")).allMatch(d -> d.matches(EXTRA_DOMAIN));
    }

    @AssertTrue(message = "{msg.validation.staticRoot}")
    public boolean isStaticRootValid() {
        return type != SiteType.STATIC
                || (staticRoot != null && staticRoot.matches(STATIC_ROOT) && !staticRoot.contains(".."));
    }

    /** 적어 둔 백엔드 주소 목록(중복 제거, 순서 유지). */
    public List<String> proxyTargets() {
        if (proxyTarget == null || proxyTarget.isBlank()) {
            return List.of();
        }
        return Arrays.stream(proxyTarget.trim().split("[\\s,]+")).distinct().toList();
    }

    /** 저장할 백엔드 주소. 여러 개면 줄마다 하나씩 둔다. */
    public String proxyTargetNormalized() {
        List<String> targets = proxyTargets();
        return targets.isEmpty() ? null : String.join("\n", targets);
    }

    @AssertTrue(message = "{msg.validation.proxyTarget}")
    public boolean isProxyTargetValid() {
        if (type != SiteType.PROXY) {
            return true;
        }
        List<String> targets = proxyTargets();
        if (targets.isEmpty() || targets.size() > MAX_BACKENDS
                || !targets.stream().allMatch(t -> t.matches(PROXY_TARGET))) {
            return false;
        }
        if (!targets.stream().map(ProxyBackend::parse).allMatch(b -> b.port() >= 1 && b.port() <= 65535)) {
            return false;
        }
        // upstream으로 묶으면 proxy_pass에 스킴과 경로가 하나만 들어가므로 모두 같아야 한다
        return targets.stream().map(ProxyBackend::parse)
                .map(b -> b.scheme() + b.path()).distinct().count() == 1;
    }

    /** 관리 화면과 상태 확인 포트로 프록시하면 관리 화면이 사이트 포트로 공개된다. */
    @AssertTrue(message = "{msg.validation.proxyNotManager}")
    public boolean isProxyTargetNotManager() {
        return type != SiteType.PROXY || !isProxyTargetValid()
                || proxyTargets().stream().map(ProxyBackend::parse).noneMatch(ProxyBackend::isManager);
    }

    /**
     * 문서 루트로 쓸 수 없는 디렉터리. 관리도구 DB(/data), 인증서 키, 실행 파일 등이 웹으로 공개되지 않게 한다.
     * 이 디렉터리 자체, 그 아래, 그리고 이들을 담은 상위 디렉터리(/, /etc 등)를 모두 막는다.
     */
    static final List<String> PROTECTED_DIRS = List.of("/data", "/etc/nginx/certs", "/app", "/proc", "/sys",
            "/run", "/root", "/var/lib/nginx-manager", "/command", "/package", "/etc/s6-overlay");

    @AssertTrue(message = "{msg.validation.staticRootSafe}")
    public boolean isStaticRootSafe() {
        if (type != SiteType.STATIC || staticRoot == null || !isStaticRootValid()) {
            return true;
        }
        String root = staticRoot.replaceAll("/+$", "");
        if (root.isEmpty()) {
            return false;
        }
        return PROTECTED_DIRS.stream().noneMatch(dir -> root.equals(dir) || root.startsWith(dir + "/")
                || dir.startsWith(root + "/"));
    }

    @AssertTrue(message = "{msg.validation.wildcardLetsEncrypt}")
    public boolean isLetsEncryptDomainsValid() {
        String normalized = extraDomainsNormalized();
        return !httpsEnabled || certificateId != null || normalized == null || !normalized.contains("*");
    }

    /** Let's Encrypt로 받을 도메인 목록(대표 도메인 먼저). */
    public List<String> certificateDomains(String primary) {
        List<String> domains = new ArrayList<>();
        domains.add(primary);
        String normalized = extraDomainsNormalized();
        if (normalized != null) {
            Arrays.stream(normalized.split(" ")).map(d -> d.toLowerCase(Locale.ROOT)).filter(d -> !domains.contains(d))
                    .forEach(domains::add);
        }
        return domains;
    }

    /** HTTPS(443), 에이전트(7070), nginx 상태 확인(127.0.0.1:8081), 관리 화면(9000)이 쓰는 포트. */
    static final List<Integer> RESERVED_PORTS = List.of(443, 7070, 8081, 9000);

    @AssertTrue(message = "{msg.validation.listenPort}")
    public boolean isListenPortFree() {
        return !RESERVED_PORTS.contains(listenHttpPort);
    }

    @AssertTrue(message = "{msg.validation.redirectTarget}")
    public boolean isRedirectTargetValid() {
        return type != SiteType.REDIRECT
                || (redirectTarget != null && redirectTarget.strip().matches(REDIRECT_TARGET));
    }

    @AssertTrue(message = "{msg.validation.redirectCode}")
    public boolean isRedirectCodeValid() {
        return type != SiteType.REDIRECT || REDIRECT_CODES.contains(redirectCode);
    }

    /** 경로를 붙일 때는 주소가 /로 끝나지 않아야 //가 생기지 않는다. 쿼리가 있는 주소에는 경로를 붙일 수 없다. */
    public String redirectTargetNormalized() {
        if (redirectTarget == null) {
            return null;
        }
        String target = redirectTarget.strip();
        return redirectKeepPath ? target.replaceAll("/+$", "") : target;
    }

    @AssertTrue(message = "{msg.validation.redirectKeepPath}")
    public boolean isRedirectKeepPathValid() {
        return type != SiteType.REDIRECT || !redirectKeepPath || redirectTarget == null || !redirectTarget.contains("?");
    }

    @AssertTrue(message = "{msg.validation.httpsOnlyOption}")
    public boolean isHttpsOptionsValid() {
        return httpsEnabled || (!options.http3() && !options.hsts());
    }

    @AssertTrue(message = "{msg.validation.rawType}")
    public boolean isFormType() {
        return type != SiteType.RAW;
    }
}
