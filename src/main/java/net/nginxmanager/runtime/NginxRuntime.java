package net.nginxmanager.runtime;

import java.time.Duration;
import java.util.Optional;

/**
 * nginx 실행 환경을 숨기는 인터페이스. 구현은 nginx 컨테이너 안의 에이전트를 부르는 {@link AgentNginxRuntime}이다.
 * 검증·reload는 ApplyService의 전역 잠금 안에서만 호출한다.
 * 에이전트에 연결하지 못하면 {@link #status()}는 UNREACHABLE을 돌려주고, 나머지는 {@link NginxCommandException}을 던진다.
 */
public interface NginxRuntime {

    RuntimeStatus status();

    /** 살아 있는 마스터 PID. 내려가 있으면 null. 자주 불러도 되도록 nginx 명령을 실행하지 않는다. */
    default Long masterPid() {
        return status().masterPid();
    }

    /** {@code nginx -t}. 설정이 틀려도 예외를 던지지 않고 결과로 돌려준다. */
    ValidationResult validate();

    /** {@code nginx -s reload}. 명령이 실패하거나 새 설정을 불러오지 못하면 {@link NginxCommandException}. */
    void reload();

    /** 마스터 프로세스가 살아 있고 stub_status가 200을 줄 때까지 timeout 동안 다시 확인한다. */
    HealthResult healthCheck(Duration timeout);

    /** {@code nginx -s reopen}. 로그 파일을 옮긴 뒤 새 파일에 쓰게 한다. 명령이 실패하면 {@link NginxCommandException}. */
    void reopenLogs();

    /** 내려가 있는 nginx를 다시 띄운다. 명령이 실패하면 {@link NginxCommandException}. */
    void start();

    /** nginx 컨테이너가 이번에 뜬 것을 가리키는 값. 컨테이너가 다시 시작하면 바뀐다. 연결하지 못하면 null. */
    default String bootId() {
        return null;
    }

    /** nginx 컨테이너가 시작될 때 한 설정 검사 결과. 아직 없거나 연결하지 못하면 비어 있다. */
    default Optional<StartupCheckReport> startupCheck() {
        return Optional.empty();
    }

    /** 에이전트에서 마지막으로 받은 응답으로 아는 것. 요청을 보내지 않는다. */
    default AgentInfo agent() {
        return AgentInfo.unknown();
    }
}
