package net.nginxmanager.startup;

import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.audit.AuditEvent;
import net.nginxmanager.audit.AuditResult;
import net.nginxmanager.common.AppVersion;
import net.nginxmanager.runtime.NginxOutputParser;
import net.nginxmanager.runtime.NginxRuntime;
import net.nginxmanager.runtime.StartupCheckReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * nginx 컨테이너의 시작 검사 결과를 읽고(FR-024), nginx 컨테이너나 관리도구가 새로 뜰 때마다
 * nginx·관리도구 버전을 기록해 바뀌었으면 감사 로그에 남긴다(FR-025).
 * 두 컨테이너는 따로 다시 시작하므로, 에이전트의 bootId가 바뀌면 nginx 컨테이너가 새로 뜬 것으로 보고 다시 읽는다.
 */
@Service
public class StartupCheckService {

    private static final Logger log = LoggerFactory.getLogger(StartupCheckService.class);

    private final NginxRuntime runtime;
    private final StartupRecordRepository recordRepository;
    private final AppVersion appVersion;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;

    private volatile StartupCheck check;
    private volatile VersionChange lastChange;
    private volatile String lastBootId;

    public StartupCheckService(NginxRuntime runtime, StartupRecordRepository recordRepository, AppVersion appVersion,
                               ApplicationEventPublisher events, TransactionTemplate tx) {
        this.runtime = runtime;
        this.recordRepository = recordRepository;
        this.appVersion = appVersion;
        this.events = events;
        this.tx = tx;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        refreshIfRestarted();
    }

    /** nginx 컨테이너가 다시 떴는지 보고, 그렇다면 시작 검사 결과와 버전을 다시 읽는다. 연결하지 못하면 다음에 본다. */
    @Scheduled(initialDelayString = "${nginx-manager.process-watch.interval:PT5S}",
            fixedDelayString = "${nginx-manager.process-watch.interval:PT5S}")
    public synchronized void refreshIfRestarted() {
        String bootId = runtime.bootId();
        if (bootId == null || Objects.equals(bootId, lastBootId)) {
            return;
        }
        check = runtime.startupCheck().map(StartupCheckService::toCheck).orElse(null);
        if (check != null && !check.ok()) {
            log.warn("startup configuration check failed, nginx was not started:\n{}", check.output());
        }
        String nginxVersion = runtime.status().version();
        if (nginxVersion == null) {
            // nginx -V를 읽지 못했다. 버전을 모르는 채로 기록하면 거짓 업그레이드가 남으므로 다음에 다시 본다
            return;
        }
        lastBootId = bootId;
        record(nginxVersion, appVersion());
    }

    private static StartupCheck toCheck(StartupCheckReport report) {
        return new StartupCheck(report.ok(), report.checkedAt(), report.output(),
                NginxOutputParser.parseTestOutput(report.output()));
    }

    /** 이번 시작의 버전을 남기고, 지난번과 다르면 감사 로그에 업그레이드(또는 되돌림)를 남긴다. */
    void record(String nginxVersion, String appVersion) {
        lastChange = tx.execute(status -> {
            StartupRecord current = recordRepository.save(new StartupRecord(nginxVersion, appVersion,
                    check == null ? null : check.ok()));
            return previousChange(current);
        });
        if (lastChange != null) {
            log.info("version changed: {}", lastChange.describe());
            events.publishEvent(new AuditEvent(null, AuditAction.UPGRADE, "runtime", AuditResult.SUCCESS,
                    lastChange.describe()));
        }
    }

    /** 이번 시작이 지난번과 버전이 다르면 그 변화. */
    private VersionChange previousChange(StartupRecord current) {
        List<StartupRecord> latest = recordRepository.findTop2ByOrderByIdDesc();
        if (latest.size() < 2 || latest.get(1).sameVersionsAs(current)) {
            return null;
        }
        StartupRecord previous = latest.get(1);
        return new VersionChange(previous.getNginxVersion(), current.getNginxVersion(),
                previous.getAppVersion(), current.getAppVersion(), current.getCreatedAt());
    }

    public Optional<StartupCheck> check() {
        return Optional.ofNullable(check);
    }

    /** 이번 시작에서 버전이 바뀌었으면 그 내용. */
    public Optional<VersionChange> versionChange() {
        return Optional.ofNullable(lastChange);
    }

    /** 관리도구 버전. 빌드 정보 없이 실행하면 dev. */
    public String appVersion() {
        return appVersion.current();
    }
}
