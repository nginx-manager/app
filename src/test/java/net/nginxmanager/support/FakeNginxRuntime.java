package net.nginxmanager.support;

import net.nginxmanager.runtime.HealthResult;
import net.nginxmanager.runtime.NginxRuntime;
import net.nginxmanager.runtime.RuntimeStatus;
import net.nginxmanager.runtime.ValidationResult;
import net.nginxmanager.runtime.ValidationResult.ValidationMessage;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

/** 테스트용 nginx. 검증·상태 확인 결과를 테스트가 정한다. */
public class FakeNginxRuntime implements NginxRuntime {

    public Supplier<ValidationResult> validation = FakeNginxRuntime::valid;
    public Supplier<HealthResult> health = HealthResult::ok;
    public int validateCalls;
    public int reloadCalls;
    /** 설정하면 validate()가 이 래치가 풀릴 때까지 기다린다(동시 적용 테스트). */
    public CountDownLatch blockValidation;
    public CountDownLatch validationStarted;

    public void reset() {
        validation = FakeNginxRuntime::valid;
        health = HealthResult::ok;
        validateCalls = 0;
        reloadCalls = 0;
        blockValidation = null;
        validationStarted = null;
    }

    public static ValidationResult valid() {
        return new ValidationResult(true, "nginx: configuration file /etc/nginx/nginx.conf test is successful", List.of());
    }

    public static ValidationResult invalid(String file, int line, String message) {
        String output = "nginx: [emerg] " + message + " in " + file + ":" + line + "\n"
                + "nginx: configuration file /etc/nginx/nginx.conf test failed";
        return new ValidationResult(false, output, List.of(new ValidationMessage("emerg", message, file, line)));
    }

    @Override
    public RuntimeStatus status() {
        return new RuntimeStatus(RuntimeStatus.State.RUNNING, "1.31.6", List.of(), 1L);
    }

    @Override
    public ValidationResult validate() {
        validateCalls++;
        if (validationStarted != null) {
            validationStarted.countDown();
        }
        if (blockValidation != null) {
            try {
                blockValidation.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return validation.get();
    }

    @Override
    public void reload() {
        reloadCalls++;
    }

    @Override
    public HealthResult healthCheck(Duration timeout) {
        return health.get();
    }
}
