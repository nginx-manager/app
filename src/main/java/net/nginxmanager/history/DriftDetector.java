package net.nginxmanager.history;

import net.nginxmanager.apply.ApplyService;
import net.nginxmanager.apply.ConfigSync;
import net.nginxmanager.apply.FileChange;
import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.audit.AuditEvent;
import net.nginxmanager.config.ConfigFile;
import net.nginxmanager.config.ConfigFileRepository;
import net.nginxmanager.config.ConfigFileStore;
import net.nginxmanager.config.ConfigVersion;
import net.nginxmanager.config.ConfigVersionRepository;
import net.nginxmanager.config.VersionResult;
import net.nginxmanager.config.VersionSource;
import net.nginxmanager.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 서버에서 직접 바뀐 설정(drift)을 찾는다. 1분마다, 그리고 화면을 열 때(최근 확인이 10초보다 오래됐으면) 확인한다.
 * 적용 중에는 파일이 잠깐 바뀌어 있으므로 확인을 건너뛴다.
 */
@Component
public class DriftDetector {

    static final Duration MAX_AGE = Duration.ofSeconds(10);

    private record Scan(Instant at, List<DriftItem> items) {
    }

    private volatile Scan last = new Scan(Instant.EPOCH, List.of());
    /** 마지막으로 알린 변경 목록(028). */
    private volatile Set<DriftItem> reported = Set.of();

    private final ApplyService applyService;
    private final ConfigSync sync;
    private final ConfigFileRepository configFileRepository;
    private final ConfigVersionRepository versionRepository;
    private final UserRepository userRepository;
    private final ConfigFileStore store;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public DriftDetector(ApplyService applyService, ConfigSync sync, ConfigFileRepository configFileRepository,
                         ConfigVersionRepository versionRepository, UserRepository userRepository,
                         ConfigFileStore store, TransactionTemplate tx, ApplicationEventPublisher events,
                         Clock clock) {
        this.applyService = applyService;
        this.sync = sync;
        this.configFileRepository = configFileRepository;
        this.versionRepository = versionRepository;
        this.userRepository = userRepository;
        this.store = store;
        this.tx = tx;
        this.events = events;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${nginx-manager.drift.interval:PT1M}",
            fixedDelayString = "${nginx-manager.drift.interval:PT1M}")
    public void scheduledScan() {
        List<DriftItem> items = refresh();
        // 같은 변경을 1분마다 알리지 않는다. 목록이 달라지면(새 파일, 가져오기·적용 뒤 다시 생김) 다시 알린다
        Set<DriftItem> found = Set.copyOf(items);
        if (!found.equals(reported)) {
            reported = found;
            if (!found.isEmpty()) {
                events.publishEvent(new DriftFoundEvent(items));
            }
        }
    }

    /** 최근 확인 결과. 오래됐으면 다시 확인한다. */
    public List<DriftItem> current() {
        Scan scan = last;
        if (Duration.between(scan.at(), clock.instant()).compareTo(MAX_AGE) > 0) {
            return refresh();
        }
        return scan.items();
    }

    /** 다음 조회 때 다시 확인하게 한다. */
    public void invalidate() {
        last = new Scan(Instant.EPOCH, List.of());
    }

    public List<DriftItem> refresh() {
        applyService.tryWithLock(() -> tx.execute(status -> scan()))
                .ifPresent(items -> last = new Scan(clock.instant(), items));
        return last.items();
    }

    /**
     * 지금 디스크 상태를 새 버전으로 가져온다. 사용자가 쓰던 초안은 그대로 둔다.
     *
     * @return 기록한 버전. 가져올 것이 없었으면 null
     */
    public Long importDrift(String actor) {
        Long versionId = applyService.withLock(() -> tx.execute(status -> {
            List<DriftItem> items = scan();
            if (items.isEmpty()) {
                return null;
            }
            List<FileChange> changes = items.stream()
                    .map(item -> new FileChange(item.path(), store.read(item.path())))
                    .toList();
            sync.sync(changes, true);
            ConfigVersion version = new ConfigVersion(userRepository.findByUsername(actor).orElse(null),
                    "드리프트 가져오기(서버에서 직접 바뀐 설정)", VersionSource.DRIFT_IMPORT, VersionResult.SUCCESS, null);
            sync.snapshot(version);
            return versionRepository.save(version).getId();
        }));
        last = new Scan(clock.instant(), List.of());
        if (versionId != null) {
            events.publishEvent(AuditEvent.success(actor, AuditAction.DRIFT_IMPORT, "version:" + versionId));
        }
        return versionId;
    }

    private List<DriftItem> scan() {
        List<DriftItem> items = new ArrayList<>();
        Set<String> known = new HashSet<>();
        for (ConfigFile file : configFileRepository.findAllByOrderByPathAsc()) {
            known.add(file.getPath());
            String content = store.read(file.getPath());
            if (content == null) {
                if (file.getLastKnownHash() != null) {
                    items.add(new DriftItem(file.getPath(), DriftItem.Kind.DELETED));
                }
            } else if (!ConfigFileStore.hash(content).equals(file.getLastKnownHash())) {
                items.add(new DriftItem(file.getPath(), DriftItem.Kind.MODIFIED));
            }
        }
        for (String path : store.listConfFiles()) {
            if (!known.contains(path)) {
                items.add(new DriftItem(path, DriftItem.Kind.ADDED));
            }
        }
        return List.copyOf(items);
    }
}
