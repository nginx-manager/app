package net.nginxmanager.runtime;

import java.util.List;

/**
 * @param version nginx 버전(예: 1.31.6). 실행 파일을 못 찾으면 null
 * @param modules {@code nginx -V}의 configure 인자에서 읽은 모듈 목록
 * @param masterPid 마스터 PID. 실행 중이 아니면 null
 */
public record RuntimeStatus(State state, String version, List<NginxModule> modules, Long masterPid) {

    public enum State {
        RUNNING,
        STOPPED
    }
}
