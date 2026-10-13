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
import java.util.concurrent.locks.ReentrantLock;

/**
 * 초안을 nginx에 반영한다: 잠금 → 백업 → 쓰기 → 검증 → reload → 상태 확인.
 * 검증에 실패하면 파일을 되돌리고, reload 뒤 상태가 나쁘면 되돌린 뒤 다시 reload한다(constitution 원칙 I).
 * nginx는 reload 전까지 파일을 다시 읽지 않으므로, 잠금 안에서 "쓰고 검증하고 되돌리기"는 서비스에 영향이 없다.
 */
@Service
public class ApplyService {

    private static final Logger log = LoggerFactory.getLogger(ApplyService.class);

    private record Change(String path, String content) {
    }

    private final ReentrantLock lock = new ReentrantLock();

    private final ConfigFileRepository configFileRepository;
    private final ConfigVersionRepository versionRepository;
    private final UserRepository userRepository;
    private final ConfigFileStore store;
    private final NginxRuntime runtime;
    private final NginxRuntimeProperties properties;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;

    public ApplyService(ConfigFileRepository configFileRepository, ConfigVersionRepository versionRepository,
                        UserRepository userRepository, ConfigFileStore store, NginxRuntime runtime,
                        NginxRuntimeProperties properties, TransactionTemplate tx,
                        ApplicationEventPublisher events) {
        this.configFileRepository = configFileRepository;
        this.versionRepository = versionRepository;
        this.userRepository = userRepository;
        this.store = store;
        this.runtime = runtime;
        this.properties = properties;
        this.tx = tx;
        this.events = events;
    }

    /** @param actor 적용한 사용자. 시스템 작업이면 null */
    public ApplyResult applyPending(String actor, String message) {
        if (!lock.tryLock()) {
            throw new ApplyInProgressException();
        }
        try {
            List<Change> changes = tx.execute(status -> configFileRepository.findAll().stream()
                    .filter(ConfigFile::hasPendingChange)
                    .map(f -> new Change(f.getPath(), f.isDraftDelete() ? null : f.getDraftContent()))
                    .toList());
            if (changes.isEmpty()) {
                return ApplyResult.nothingToApply();
            }
            return apply(changes, actor, message);
        } finally {
            lock.unlock();
        }
    }

    private ApplyResult apply(List<Change> changes, String actor, String message) {
        List<String> paths = changes.stream().map(Change::path).toList();
        Map<String, String> backup = new LinkedHashMap<>();
        for (String path : paths) {
            backup.put(path, store.read(path));
        }

        try {
            changes.forEach(change -> store.write(change.path(), change.content()));
        } catch (RuntimeException e) {
            restore(backup);
            throw e;
        }

        ValidationResult validation = runtime.validate();
        if (!validation.ok()) {
            restore(backup);
            log.info("Apply rejected by nginx -t: {}", validation.errors());
            return record(changes, actor, message, VersionResult.VALIDATION_FAILED, validation.output(),
                    validation.errors());
        }

        HealthResult health;
        try {
            runtime.reload();
            health = runtime.healthCheck(properties.healthTimeout());
        } catch (NginxCommandException e) {
            health = HealthResult.failed(e.getMessage());
        }
        if (!health.healthy()) {
            log.warn("nginx unhealthy after reload ({}), restoring previous configuration", health.detail());
            restore(backup);
            try {
                runtime.reload();
            } catch (NginxCommandException e) {
                log.error("reload after restore failed", e);
            }
            return record(changes, actor, message, VersionResult.AUTO_RECOVERED, health.detail(), List.of());
        }

        return record(changes, actor, message, VersionResult.SUCCESS, validation.output(), validation.errors());
    }

    private void restore(Map<String, String> backup) {
        backup.forEach(store::write);
    }

    private ApplyResult record(List<Change> changes, String actor, String message, VersionResult result,
                               String detail, List<ValidationResult.ValidationMessage> messages) {
        Long versionId = tx.execute(status -> {
            User author = actor == null ? null : userRepository.findByUsername(actor).orElse(null);
            ConfigVersion version = new ConfigVersion(author, message, VersionSource.APPLY, result, detail);
            if (result == VersionResult.SUCCESS) {
                markApplied(changes);
                // 성공한 버전은 되돌리기에 쓰도록 관리 중인 모든 파일의 내용을 남긴다
                configFileRepository.findAllByOrderByPathAsc()
                        .forEach(f -> version.addFile(f.getPath(), store.read(f.getPath())));
            } else {
                changes.forEach(c -> version.addFile(c.path(), c.content()));
            }
            return versionRepository.save(version).getId();
        });
        List<String> paths = changes.stream().map(Change::path).toList();
        events.publishEvent(result == VersionResult.SUCCESS
                ? AuditEvent.success(actor, AuditAction.APPLY, "version:" + versionId)
                : AuditEvent.failure(actor, AuditAction.APPLY, "version:" + versionId, result.name()));
        return new ApplyResult(result, versionId, paths, messages, detail);
    }

    private void markApplied(List<Change> changes) {
        for (Change change : changes) {
            configFileRepository.findByPath(change.path()).ifPresent(file -> {
                if (change.content() == null) {
                    configFileRepository.delete(file);
                } else {
                    file.markApplied(ConfigFileStore.hash(change.content()));
                }
            });
        }
    }
}
