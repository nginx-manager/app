package net.nginxmanager.runtime;

import java.util.List;

/**
 * @param version nginx 버전(예: 1.31.6). 실행 파일을 못 찾거나 에이전트에 연결하지 못하면 null
 * @param modules {@code nginx -V}의 configure 인자에서 읽은 모듈 목록
 * @param masterPid 마스터 PID(nginx 컨테이너 안의 번호). 실행 중이 아니면 null
 */
public record RuntimeStatus(State state, String version, List<NginxModule> modules, Long masterPid) {

    public enum State {
        RUNNING,
        STOPPED,
        /** nginx 컨테이너의 에이전트에 연결하지 못해 nginx 상태를 모른다 */
        UNREACHABLE
    }

    public static RuntimeStatus unreachable() {
        return new RuntimeStatus(State.UNREACHABLE, null, List.of(), null);
    }
}
