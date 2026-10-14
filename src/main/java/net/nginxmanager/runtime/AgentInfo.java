package net.nginxmanager.runtime;

/**
 * 에이전트에서 마지막으로 받은 응답으로 아는 것.
 *
 * @param reachable 마지막 요청에 응답했으면 true. 아직 요청하지 않았거나 연결하지 못했으면 false
 * @param version   에이전트(nginx 이미지)의 관리도구 릴리스 버전. 모르면 null
 * @param protocol  에이전트 API 판. 모르면 null
 * @param error     연결하지 못했거나 거절당한 이유
 */
public record AgentInfo(boolean reachable, String version, Integer protocol, String error) {

    public static AgentInfo unknown() {
        return new AgentInfo(false, null, null, null);
    }
}
