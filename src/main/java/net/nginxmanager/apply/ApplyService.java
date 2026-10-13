package net.nginxmanager.apply;

import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.audit.AuditEvent;
import net.nginxmanager.config.ConfigFile;
import net.nginxmanager.config.ConfigFileRepository;
import net.nginxmanager.config.ConfigFileStore;
import net.nginxmanager.config.ConfigVersion;
import net.nginxmanager.config.ConfigVersionRepository;
import net.nginxmanager.config.VersionResult;
import net.nginxmanager.config.VersionSource;
import net.nginxmanager.runtime.HealthResult;
import net.nginxmanager.runtime.NginxCommandException;
import net.nginxmanager.runtime.NginxRuntime;
import net.nginxmanager.runtime.NginxRuntimeProperties;
import net.nginxmanager.runtime.RuntimeStatus;
import net.nginxmanager.runtime.ValidationResult;
import net.nginxmanager.user.User;
import net.nginxmanager.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 초안을 nginx에 반영한다: 잠금 → 백업 → 쓰기 → 검증 → reload → 상태 확인.
 * 검증에 실패하면 파일을 되돌리고, reload 뒤 상태가 나쁘면 되돌린 뒤 다시 reload한다(constitution 원칙 I).
 * nginx는 reload 전까지 파일을 다시 읽지 않으므로, 잠금 안에서 "쓰고 검증하고 되돌리기"는 서비스에 영향이 없다.
 */
@Service
public class ApplyService {

    static final String RELOAD_SKIPPED = "nginx가 멈춰 있어 reload하지 않았습니다. 대시보드에서 nginx를 시작하세요.";

    private static final Logger log = LoggerFactory.getLogger(ApplyService.class);

    private final ConfigFileRepository configFileRepository;
    private final ConfigVersionRepository versionRepository;
    private final UserRepository userRepository;
    private final ConfigFileStore store;
    private final NginxRuntime runtime;
    private final NginxRuntimeProperties properties;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final ConfigSync sync;
    private final ApplyLock lock;

    public ApplyService(ConfigFileRepository configFileRepository, ConfigVersionRepository versionRepository,
                        UserRepository userRepository, ConfigFileStore store, NginxRuntime runtime,
                        NginxRuntimeProperties properties, TransactionTemplate tx,
                        ApplicationEventPublisher events, ConfigSync sync, ApplyLock lock) {
        this.configFileRepository = configFileRepository;
        this.versionRepository = versionRepository;
        this.userRepository = userRepository;
        this.store = store;
        this.runtime = runtime;
        this.properties = properties;
        this.tx = tx;
        this.events = events;
        this.sync = sync;
        this.lock = lock;
    }

    /**
     * 적용이나 되돌리기가 기록할 버전의 출처.
     *
     * @param onSuccess 반영에 성공하면 같은 트랜잭션 안에서, 사이트 동기화보다 먼저 실행한다(인증서 활성화 등)
     */
    public record Origin(String actor, String message, VersionSource source, Long rolledBackToId, Runnable onSuccess) {

        public Origin(String actor, String message, VersionSource source, Long rolledBackToId) {
            this(actor, message, source, rolledBackToId, null);
        }
    }

    /** @param actor 적용한 사용자. 시스템 작업이면 null */
    public ApplyResult applyPending(String actor, String message) {
        return applyChanges(() -> tx.execute(status -> configFileRepository.findAll().stream()
                        .filter(ConfigFile::hasPendingChange)
                        .map(f -> new FileChange(f.getPath(), f.isDraftDelete() ? null : f.getDraftContent()))
                        .toList()),
                new Origin(actor, message, VersionSource.APPLY, null));
    }

    /**
     * 초안 대신 주어진 변경을 같은 절차로 반영한다(되돌리기 등). 변경 계산도 잠금 안에서 한다.
     */
    public ApplyResult applyChanges(Supplier<List<FileChange>> changes, Origin origin) {
        return withLock(() -> {
            List<FileChange> computed = changes.get();
            if (computed.isEmpty()) {
                return ApplyResult.nothingToApply();
            }
            return apply(computed, origin);
        });
    }

    /** 적용과 겹치면 안 되는 작업을 잠금 안에서 실행한다. 이미 적용 중이면 기다리지 않고 거절한다. */
    public <T> T withLock(Supplier<T> work) {
        return lock.withLock(work);
    }

    /** 잠금을 얻으면 실행하고, 적용 중이면 건너뛴다. */
    public <T> Optional<T> tryWithLock(Supplier<T> work) {
        return lock.tryWithLock(work);
    }

    private ApplyResult apply(List<FileChange> changes, Origin origin) {
        List<String> paths = changes.stream().map(FileChange::path).toList();
        Map<String, String> backup = new LinkedHashMap<>();
        for (String path : paths) {
            backup.put(path, store.read(path));
        }

        boolean[] reloaded = {false};
        try {
            changes.forEach(change -> store.write(change.path(), change.content()));
            return verifyAndRecord(changes, origin, backup, reloaded);
        } catch (RuntimeException e) {
            // nginx -t 시간 초과, pid 파일 오류, DB 오류 등 예상하지 못한 실패: 검증되지 않은 파일을 남기지 않는다
            log.error("apply failed unexpectedly, restoring previous configuration", e);
            restore(backup);
            if (reloaded[0]) {
                try {
                    runtime.reload();
                } catch (RuntimeException reloadFailure) {
                    log.error("reload after restore failed", reloadFailure);
                }
            }
            throw e;
        }
    }

    private ApplyResult verifyAndRecord(List<FileChange> changes, Origin origin, Map<String, String> backup,
                                        boolean[] reloaded) {
        ValidationResult validation = runtime.validate();
        if (!validation.ok()) {
            restore(backup);
            log.info("Apply rejected by nginx -t: {}", validation.errors());
            return record(changes, origin, VersionResult.VALIDATION_FAILED, validation.output(),
                    validation.errors());
        }

        // nginx가 내려가 있으면(시작 검사 실패 등) reload할 대상이 없다. 검증된 설정만 남기고, 시작은 사용자가 한다
        if (runtime.status().state() != RuntimeStatus.State.RUNNING) {
            log.info("nginx is not running; configuration written without reload");
            return record(changes, origin, VersionResult.SUCCESS,
                    validation.output() + "\n" + RELOAD_SKIPPED,
                    validation.errors());
        }

        HealthResult health;
        try {
            reloaded[0] = true;
            runtime.reload();
            health = runtime.healthCheck(properties.healthTimeout());
        } catch (NginxCommandException e) {
            health = HealthResult.failed(e.getMessage());
        }
        if (!health.healthy()) {
            log.warn("nginx unhealthy after reload ({}), restoring previous configuration", health.detail());
            restore(backup);
            reloaded[0] = false;
            try {
                runtime.reload();
            } catch (NginxCommandException e) {
                log.error("reload after restore failed", e);
            }
            return record(changes, origin, VersionResult.AUTO_RECOVERED, health.detail(), List.of());
        }

        return record(changes, origin, VersionResult.SUCCESS, validation.output(), validation.errors());
    }

    private void restore(Map<String, String> backup) {
        backup.forEach(store::write);
    }

    private ApplyResult record(List<FileChange> changes, Origin origin, VersionResult result,
                               String detail, List<ValidationResult.ValidationMessage> messages) {
        Long versionId = tx.execute(status -> {
            User author = origin.actor() == null ? null : userRepository.findByUsername(origin.actor()).orElse(null);
            ConfigVersion version = new ConfigVersion(author, origin.message(), origin.source(), result, detail)
                    .rolledBackTo(origin.rolledBackToId());
            if (result == VersionResult.SUCCESS) {
                if (origin.onSuccess() != null) {
                    origin.onSuccess().run();
                }
                // 사용자 적용만 초안을 비운다. 시스템 작업(인증서 등)은 사용자가 쓰던 초안을 건드리지 않는다
                sync.sync(changes, origin.source() != VersionSource.APPLY && origin.source() != VersionSource.ROLLBACK);
                // 성공한 버전은 되돌리기에 쓰도록 관리 중인 모든 파일의 내용을 남긴다
                sync.snapshot(version);
            } else {
                changes.forEach(c -> version.addFile(c.path(), c.content()));
            }
            return versionRepository.save(version).getId();
        });
        List<String> paths = changes.stream().map(FileChange::path).toList();
        if (result == VersionResult.SUCCESS) {
            events.publishEvent(new ConfigAppliedEvent(versionId, origin.source(), paths));
        }
        AuditAction action = switch (origin.source()) {
            case ROLLBACK -> AuditAction.ROLLBACK;
            case CERT_ISSUE -> AuditAction.CERT_ISSUE;
            case CERT_RENEW -> AuditAction.CERT_RENEW;
            default -> AuditAction.APPLY;
        };
        events.publishEvent(result == VersionResult.SUCCESS
                ? AuditEvent.success(origin.actor(), action, "version:" + versionId)
                : AuditEvent.failure(origin.actor(), action, "version:" + versionId, result.name()));
        return new ApplyResult(result, versionId, paths, messages, detail);
    }
}
