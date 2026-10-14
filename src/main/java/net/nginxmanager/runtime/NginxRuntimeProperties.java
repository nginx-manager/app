package net.nginxmanager.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/**
 * @param configRoot     nginx 설정 루트. nginx 컨테이너와 같은 볼륨을 같은 경로에 둔다
 * @param commandTimeout 에이전트가 nginx 명령을 끝내기를 기다리는 시간
 * @param healthTimeout  reload 뒤 nginx가 정상인지 확인하는 최대 시간
 * @param logDir         로그 루트. 사이트 로그는 sites/ 아래에 있다
 * @param agent          nginx 컨테이너 안의 에이전트
 */
@ConfigurationProperties("nginx.runtime")
public record NginxRuntimeProperties(
        Path configRoot,
        Duration commandTimeout,
        Duration healthTimeout,
        Path logDir,
        Agent agent) {

    /**
     * @param url       에이전트 주소(예: http://nginx:7070)
     * @param token     에이전트 토큰. 비우면 tokenFile에서 읽는다
     * @param tokenFile 에이전트가 처음 뜰 때 만들어 두는 토큰 파일(공유 볼륨)
     */
    public record Agent(URI url, String token, Path tokenFile) {

        public Agent {
            if (url == null) {
                url = URI.create("http://nginx:7070");
            }
            if (tokenFile == null) {
                tokenFile = Path.of("/var/lib/nginx-manager/agent/token");
            }
        }
    }

    public NginxRuntimeProperties {
        if (logDir == null) {
            logDir = Path.of("/var/log/nginx");
        }
        if (agent == null) {
            agent = new Agent(null, null, null);
        }
    }
}
