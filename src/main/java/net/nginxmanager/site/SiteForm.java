package net.nginxmanager.site;

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
 */
public record SiteForm(
        @Pattern(regexp = SiteForm.DOMAIN, message = "도메인 형식이 아닙니다.")
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
        @Min(value = 1, message = "타임아웃은 1~3600초입니다.") @Max(value = 3600, message = "타임아웃은 1~3600초입니다.")
        Integer proxyConnectTimeout,
        @Min(value = 1, message = "타임아웃은 1~3600초입니다.") @Max(value = 3600, message = "타임아웃은 1~3600초입니다.")
        Integer proxyReadTimeout,
        LoadBalancing proxyLoadBalancing) {

    /** 백엔드는 이 수까지 적을 수 있다. */
    static final int MAX_BACKENDS = 32;

    /** 체크박스 값이 아예 없으면(폼 밖의 요청) HTTPS는 끄고, 켰을 때 HTTP는 HTTPS로 넘긴다. */
    public SiteForm {
        httpsEnabled = httpsEnabled != null && httpsEnabled;
        redirectHttpToHttps = redirectHttpToHttps == null || redirectHttpToHttps;
        proxyWebsocket = proxyWebsocket != null && proxyWebsocket;
        proxyLoadBalancing = proxyLoadBalancing == null ? LoadBalancing.ROUND_ROBIN : proxyLoadBalancing;
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
                redirectHttpToHttps, null, proxyWebsocket, proxyConnectTimeout, proxyReadTimeout, proxyLoadBalancing);
    }

    static final String DOMAIN =
            "(?=.{1,253}$)([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)*[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?";
    private static final String EXTRA_DOMAIN = "(\\*\\.)?" + DOMAIN;
    private static final String STATIC_ROOT = "/[A-Za-z0-9._/-]*";
    private static final String PROXY_TARGET = "https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[A-Za-z0-9._~/-]*)?";

    public static SiteForm empty() {
        return new SiteForm("", "", SiteType.PROXY, 80, "", "", null, false, true, null);
    }

    public String extraDomainsNormalized() {
        if (extraDomains == null || extraDomains.isBlank()) {
            return null;
        }
        return Arrays.stream(extraDomains.trim().split("[\\s,]+")).distinct().collect(Collectors.joining(" "));
    }

    @AssertTrue(message = "추가 도메인 형식이 맞지 않습니다. 공백이나 쉼표로 구분해 주세요.")
    public boolean isExtraDomainsValid() {
        String normalized = extraDomainsNormalized();
        return normalized == null || Arrays.stream(normalized.split(" ")).allMatch(d -> d.matches(EXTRA_DOMAIN));
    }

    @AssertTrue(message = "정적 사이트는 / 로 시작하는 문서 루트가 필요합니다(.. 불가).")
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

    @AssertTrue(message = "프록시 대상은 http://호스트:포트 형식이어야 합니다. 여러 개면 줄마다 하나씩, 같은 http/https와 같은 경로로 적어 주세요(최대 32개).")
    public boolean isProxyTargetValid() {
        if (type != SiteType.PROXY) {
            return true;
        }
        List<String> targets = proxyTargets();
        if (targets.isEmpty() || targets.size() > MAX_BACKENDS
                || !targets.stream().allMatch(t -> t.matches(PROXY_TARGET))) {
            return false;
        }
        // upstream으로 묶으면 proxy_pass에 스킴과 경로가 하나만 들어가므로 모두 같아야 한다
        return targets.stream().map(ProxyBackend::parse)
                .map(b -> b.scheme() + b.path()).distinct().count() == 1;
    }

    @AssertTrue(message = "와일드카드 도메인(*.)은 Let's Encrypt 자동 발급(HTTP 인증)으로 받을 수 없습니다. 인증서를 업로드해 주세요.")
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

    /** 관리 화면(9000)과 nginx 상태 확인(127.0.0.1:8081)이 쓰는 포트. */
    static final List<Integer> RESERVED_PORTS = List.of(8081, 9000);

    @AssertTrue(message = "8081, 9000번 포트는 관리도구가 씁니다. 다른 포트를 골라 주세요.")
    public boolean isListenPortFree() {
        return !RESERVED_PORTS.contains(listenHttpPort);
    }

    @AssertTrue(message = "원문 편집 사이트는 폼으로 만들 수 없습니다.")
    public boolean isFormType() {
        return type != SiteType.RAW;
    }
}
