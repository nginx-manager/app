package net.nginxmanager.runtime;

import net.nginxmanager.common.AppVersion;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 관리도구와 nginx 컨테이너(에이전트)가 서로 맞는지 본다. 마지막으로 받은 응답으로 판단하며 요청을 보내지 않는다.
 * 프로세스 감시가 몇 초마다 에이전트를 부르므로 값은 그만큼 새롭다.
 * 두 이미지는 따로 올린다(manager만 올리면 nginx는 끊기지 않는다). 릴리스 버전이 달라도 API 판이 맞으면 정상이다.
 */
@Component
public class RuntimeCompatibility {

    public enum Kind {
        /** 에이전트에 연결하지 못했거나 토큰이 맞지 않는다 */
        UNREACHABLE,
        /** API 판이 달라 요청을 주고받을 수 없다 */
        PROTOCOL
    }

    public record Warning(Kind kind, String appVersion, String agentVersion, Integer agentProtocol,
                          int appProtocol, String detail) {
    }

    private final NginxRuntime runtime;
    private final AppVersion appVersion;

    public RuntimeCompatibility(NginxRuntime runtime, AppVersion appVersion) {
        this.runtime = runtime;
        this.appVersion = appVersion;
    }

    public Optional<Warning> current() {
        AgentInfo agent = runtime.agent();
        String app = appVersion.current();
        Kind kind;
        if (agent.protocol() != null && !AgentNginxRuntime.supports(agent.protocol())) {
            kind = Kind.PROTOCOL;
        } else if (!agent.reachable() && agent.error() != null) {
            kind = Kind.UNREACHABLE;
        } else {
            return Optional.empty();
        }
        return Optional.of(new Warning(kind, app, agent.version(), agent.protocol(), AgentNginxRuntime.PROTOCOL,
                agent.error()));
    }
}
