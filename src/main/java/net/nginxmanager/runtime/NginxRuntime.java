package net.nginxmanager.runtime;

import java.time.Duration;

/**
 * nginx 실행 환경을 숨기는 인터페이스. v1 구현은 같은 컨테이너의 nginx를 다루는 {@link LocalNginxRuntime}이다.
 * 검증·reload는 ApplyService의 전역 잠금 안에서만 호출한다.
 */
public interface NginxRuntime {

    RuntimeStatus status();

    /** {@code nginx -t}. 실패해도 예외를 던지지 않고 결과로 돌려준다. */
    ValidationResult validate();

    /** {@code nginx -s reload}. 명령이 실패하면 {@link NginxCommandException}. */
    void reload();

    /** 마스터 프로세스가 살아 있고 stub_status가 200을 줄 때까지 timeout 동안 다시 확인한다. */
    HealthResult healthCheck(Duration timeout);

    /** 내려가 있는 nginx를 s6로 다시 띄운다({@code s6-svc -u}). 명령이 실패하면 {@link NginxCommandException}. */
    void start();
}
