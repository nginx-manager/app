package net.nginxmanager.runtime;

import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.audit.AuditEvent;
import net.nginxmanager.audit.AuditResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * nginx 마스터 프로세스가 바뀌었는지(죽어서 s6가 다시 띄움) 지켜보고 기록한다.
 * reload는 마스터를 바꾸지 않으므로 재시작으로 세지 않는다.
 */
@Component
public class NginxProcessWatcher {

    private static final Logger log = LoggerFactory.getLogger(NginxProcessWatcher.class);

    /** 마스터가 바뀐 때. down이면 내려간 것을 처음 본 때. */
    public record ProcessEvent(Instant at, Long previousPid, Long currentPid) {

        public boolean down() {
            return currentPid == null;
        }
    }

    private final NginxRuntime runtime;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    private volatile boolean initialized;
    private volatile Long lastPid;
    /** 내려갔다 다시 뜬 경우에도 이전 마스터를 알 수 있도록 마지막으로 살아 있던 PID를 둔다. */
    private volatile Long lastLivePid;
    private volatile ProcessEvent lastEvent;

    public NginxProcessWatcher(NginxRuntime runtime, ApplicationEventPublisher events, Clock clock) {
        this.runtime = runtime;
        this.events = events;
        this.clock = clock;
    }

    /** 관리도구가 뜬 때의 마스터를 기준으로 삼는다. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        check();
    }

    @Scheduled(initialDelayString = "${nginx-manager.process-watch.interval:PT5S}",
            fixedDelayString = "${nginx-manager.process-watch.interval:PT5S}")
    public synchronized void check() {
        Long pid;
        try {
            pid = runtime.masterPid();
        } catch (RuntimeException e) {
            log.debug("cannot read nginx pid: {}", e.getMessage());
            return;
        }
        if (!initialized) {
            initialized = true;
            lastPid = pid;
            lastLivePid = pid;
            return;
        }
        if (Objects.equals(lastPid, pid)) {
            return;
        }
        Long previous = pid == null ? lastPid : lastLivePid;
        lastPid = pid;
        if (pid != null) {
            lastLivePid = pid;
        }
        ProcessEvent event = new ProcessEvent(clock.instant(), previous, pid);
        lastEvent = event;
        if (pid == null) {
            log.warn("nginx master process {} is gone", previous);
            events.publishEvent(new AuditEvent(null, AuditAction.NGINX_RESTART, "nginx", AuditResult.FAILURE,
                    "nginx 마스터 프로세스(" + previous + ")가 내려갔습니다."));
        } else if (previous != null) {
            log.warn("nginx master process restarted: {} -> {}", previous, pid);
            events.publishEvent(new AuditEvent(null, AuditAction.NGINX_RESTART, "nginx", AuditResult.SUCCESS,
                    "nginx가 다시 시작됐습니다(PID " + previous + " → " + pid + ")."));
        } else {
            events.publishEvent(new AuditEvent(null, AuditAction.NGINX_RESTART, "nginx", AuditResult.SUCCESS,
                    "nginx가 시작됐습니다(PID " + pid + ")."));
        }
    }

    /** 관리도구가 뜬 뒤 마지막으로 본 프로세스 변화. */
    public Optional<ProcessEvent> lastEvent() {
        return Optional.ofNullable(lastEvent);
    }
}
