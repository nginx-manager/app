package net.nginxmanager.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 관리 화면을 열 도메인(설치 묶음의 .env).
 *
 * @param domain    비우면 관리 화면 프록시를 만들지 않는다(127.0.0.1:9000으로만 들어온다)
 * @param email     Let's Encrypt 연락 이메일. 화면의 설정이 비어 있을 때만 쓴다
 * @param allow     관리 도메인에 들어올 수 있는 주소(CIDR). 비우면 모두
 * @param httpsPort nginx가 서버에 여는 HTTPS 포트. 443이 아니면 리다이렉트 주소에 붙인다
 * @param upstream  nginx가 관리 화면으로 넘길 주소
 * @param syncOnStart 시작할 때 설정과 인증서를 맞출지(기본 true)
 */
@ConfigurationProperties("nginx-manager.admin")
public record AdminDomainProperties(String domain, String email, List<String> allow, Integer httpsPort,
                                    String upstream, Boolean syncOnStart) {

    public AdminDomainProperties {
        domain = domain == null || domain.isBlank() ? null : domain.trim().toLowerCase();
        email = email == null || email.isBlank() ? null : email.trim();
        allow = allow == null ? List.of() : allow.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (httpsPort == null) {
            httpsPort = 443;
        }
        if (upstream == null || upstream.isBlank()) {
            upstream = "http://manager:9000";
        }
        if (syncOnStart == null) {
            syncOnStart = true;
        }
    }

    public boolean enabled() {
        return domain != null;
    }
}
