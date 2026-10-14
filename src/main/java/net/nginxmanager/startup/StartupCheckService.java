package net.nginxmanager.startup;

import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.audit.AuditEvent;
import net.nginxmanager.audit.AuditResult;
import net.nginxmanager.runtime.NginxOutputParser;
import net.nginxmanager.runtime.NginxRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * 시작 검사 결과를 읽고(FR-024), 시작할 때마다 nginx·관리도구 버전을 기록해 바뀌었으면 감사 로그에 남긴다(FR-025).
 */
@Service
@EnableConfigurationProperties(StartupProperties.class)
public class StartupCheckService {

    private static final Logger log = LoggerFactory.getLogger(StartupCheckService.class);

    private final StartupProperties properties;
    private final NginxRuntime runtime;
    private final StartupRecordRepository recordRepository;
    private final ObjectProvider<BuildProperties> buildProperties;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;

    private volatile StartupCheck check;
    private volatile VersionChange lastChange;

    public StartupCheckService(StartupProperties properties, NginxRuntime runtime,
                               StartupRecordRepository recordRepository, ObjectProvider<BuildProperties> buildProperties,
                               ApplicationEventPublisher events, TransactionTemplate tx) {
        this.properties = properties;
        this.runtime = runtime;
        this.recordRepository = recordRepository;
        this.buildProperties = buildProperties;
        this.events = events;
        this.tx = tx;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        check = readCheck(properties.checkDir()).orElse(null);
        if (check != null && !check.ok()) {
            log.warn("startup configuration check failed, nginx was not started:\n{}", check.output());
        }
        record(runtime.status().version(), appVersion());
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

    /** 빌드 정보의 관리도구 버전. 빌드 정보 없이 실행하면 dev. */
    public String appVersion() {
        BuildProperties build = buildProperties.getIfAvailable();
        return build == null ? "dev" : build.getVersion();
    }

    static Optional<StartupCheck> readCheck(Path dir) {
        Path result = dir.resolve("result");
        if (!Files.isRegularFile(result)) {
            return Optional.empty();
        }
        try {
            boolean ok = Files.readString(result, StandardCharsets.UTF_8).strip().equals("ok");
            String output = readOptional(dir.resolve("output"));
            Instant checkedAt = parseInstant(readOptional(dir.resolve("checked-at")).strip());
            return Optional.of(new StartupCheck(ok, checkedAt, output, NginxOutputParser.parseTestOutput(output)));
        } catch (IOException e) {
            log.warn("cannot read startup check result in {}: {}", dir, e.getMessage());
            return Optional.empty();
        }
    }

    private static String readOptional(Path file) throws IOException {
        return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    }

    private static Instant parseInstant(String value) {
        try {
            return value.isEmpty() ? null : Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
