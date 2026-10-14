package net.nginxmanager.cert;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * @param directoryUrl  설정하면 화면의 환경 선택 대신 이 ACME 서버를 쓴다(테스트용 Pebble 등)
 * @param webroot       HTTP-01 챌린지 파일을 쓰는 곳. nginx가 /.well-known/acme-challenge/ 로 내보낸다
 * @param accountDir    ACME 계정 키를 두는 곳(볼륨)
 * @param timeout       챌린지와 발급을 기다리는 최대 시간
 */
@ConfigurationProperties("nginx-manager.acme")
public record AcmeProperties(String directoryUrl, Path webroot, Path accountDir, Duration timeout) {

    public AcmeProperties {
        if (directoryUrl != null && directoryUrl.isBlank()) {
            directoryUrl = null;
        }
        if (webroot == null) {
            webroot = Path.of("/var/lib/nginx-manager/acme-webroot");
        }
        if (accountDir == null) {
            accountDir = Path.of("/data/acme");
        }
        if (timeout == null) {
            timeout = Duration.ofMinutes(2);
        }
    }
}
