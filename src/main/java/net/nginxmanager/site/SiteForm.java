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
        Long certificateId) {

    /** 체크박스 값이 아예 없으면(폼 밖의 요청) HTTPS는 끄고, 켰을 때 HTTP는 HTTPS로 넘긴다. */
    public SiteForm {
        httpsEnabled = httpsEnabled != null && httpsEnabled;
        redirectHttpToHttps = redirectHttpToHttps == null || redirectHttpToHttps;
    }

    /** HTTPS 없이 쓰던 폼. */
    public SiteForm(String primaryDomain, String extraDomains, SiteType type, int listenHttpPort, String staticRoot,
                    String proxyTarget, Long version) {
        this(primaryDomain, extraDomains, type, listenHttpPort, staticRoot, proxyTarget, version, false, true, null);
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

    @AssertTrue(message = "프록시 대상은 http://호스트:포트 형식이어야 합니다.")
    public boolean isProxyTargetValid() {
        return type != SiteType.PROXY || (proxyTarget != null && proxyTarget.matches(PROXY_TARGET));
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

    @AssertTrue(message = "원문 편집 사이트는 폼으로 만들 수 없습니다.")
    public boolean isFormType() {
        return type != SiteType.RAW;
    }
}
