package net.nginxmanager.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties("nginx.runtime")
public record NginxRuntimeProperties(
        Path binary,
        Path configRoot,
        Path pidFile,
        URI statusUrl,
        Duration commandTimeout,
        Duration healthTimeout,
        /** 로그 루트. 사이트 로그는 sites/ 아래에 있다 */
        Path logDir,
        /** nginx를 감독하는 s6 서비스 디렉터리 */
        Path s6Service,
        Path s6Svc) {

    public NginxRuntimeProperties {
        if (logDir == null) {
            logDir = Path.of("/var/log/nginx");
        }
        if (s6Service == null) {
            s6Service = Path.of("/run/service/nginx");
        }
        if (s6Svc == null) {
            s6Svc = Path.of("/command/s6-svc");
        }
    }
}
