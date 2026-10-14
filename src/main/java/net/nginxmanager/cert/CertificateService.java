package net.nginxmanager.cert;

import net.nginxmanager.apply.ApplyInProgressException;
import net.nginxmanager.apply.ApplyResult;
import net.nginxmanager.apply.ApplyService;
import net.nginxmanager.apply.ConfigAppliedEvent;
import net.nginxmanager.apply.FileChange;
import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.audit.AuditEvent;
import net.nginxmanager.common.NotFoundException;
import net.nginxmanager.config.ConfigFile;
import net.nginxmanager.config.ConfigFileRepository;
import net.nginxmanager.config.ConfigFileStore;
import net.nginxmanager.config.VersionSource;
import net.nginxmanager.notify.NotificationService;
import net.nginxmanager.site.Site;
import net.nginxmanager.site.SiteConfigRenderer;
import net.nginxmanager.site.SiteRendering;
import net.nginxmanager.site.SiteRepository;
import net.nginxmanager.site.SiteType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Let's Encrypt 인증서를 발급·갱신하고 사람 개입 없이 nginx에 반영한다.
 * 새 인증서는 새 디렉터리에 쓰고, 그 경로를 쓰는 설정으로 바꿔 적용 절차(검증 → reload → 상태 확인 → 실패 시 복구)를 거친다.
 * 적용이 실패하면 이전 인증서로 계속 서비스하고 알림을 보낸다.
 */
@Service
public class CertificateService {

    private static final Logger log = LoggerFactory.getLogger(CertificateService.class);
    private static final Duration APPLY_WAIT = Duration.ofMinutes(2);

    record Target(String name, List<String> domains, CertificateSource source, CertificatePaths paths) {
    }

    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    private final TlsCertificateRepository certificateRepository;
    private final SiteRepository siteRepository;
    private final ConfigFileRepository configFileRepository;
    private final ConfigFileStore store;
    private final SiteRendering rendering;
    private final SiteConfigRenderer renderer;
    private final CertificateFiles certificateFiles;
    private final AcmeClient acme;
    private final ApplyService applyService;
    private final TransactionTemplate tx;
    private final NotificationService notifications;
    private final ApplicationEventPublisher events;
    private final TaskExecutor executor;
    private final Clock clock;

    public CertificateService(TlsCertificateRepository certificateRepository, SiteRepository siteRepository,
                              ConfigFileRepository configFileRepository, ConfigFileStore store,
                              SiteRendering rendering, SiteConfigRenderer renderer, CertificateFiles certificateFiles,
                              AcmeClient acme, ApplyService applyService, TransactionTemplate tx,
                              NotificationService notifications, ApplicationEventPublisher events,
                              @Qualifier("certificateExecutor") TaskExecutor executor, Clock clock) {
        this.certificateRepository = certificateRepository;
        this.siteRepository = siteRepository;
        this.configFileRepository = configFileRepository;
        this.store = store;
        this.rendering = rendering;
        this.renderer = renderer;
        this.certificateFiles = certificateFiles;
        this.acme = acme;
        this.applyService = applyService;
        this.tx = tx;
        this.notifications = notifications;
        this.events = events;
        this.executor = executor;
        this.clock = clock;
    }

    /** HTTPS를 켠 사이트가 적용되면 발급을 이어서 진행한다. */
    @EventListener
    public void onApplied(ConfigAppliedEvent event) {
        if (event.source() != VersionSource.APPLY) {
            return;
        }
        List<Long> due = tx.execute(status -> event.paths().stream()
                .flatMap(path -> configFileRepository.findByPath(path).stream())
                .flatMap(file -> siteRepository.findByConfigFileId(file.getId()).stream())
                .filter(Site::isHttpsEnabled)
                .map(Site::getCertificateId)
                .filter(Objects::nonNull)
                .distinct()
                .filter(id -> certificateRepository.findById(id).map(c -> c.isDue(clock.instant())).orElse(false))
                .toList());
        due.forEach(this::submit);
    }

    /** 백그라운드에서 발급·갱신한다. */
    public void submit(long certificateId) {
        executor.execute(() -> issueOrRenew(certificateId));
    }

    /**
     * 발급하거나 갱신해 반영한다. 같은 인증서를 동시에 두 번 처리하지 않는다.
     *
     * @return 실패 사유. 성공하면 비어 있다
     */
    public Optional<String> issueOrRenew(long certificateId) {
        if (!running.add(certificateId)) {
            return Optional.of("이미 진행 중입니다.");
        }
        try {
            return run(certificateId);
        } catch (RuntimeException e) {
            log.error("certificate {} failed", certificateId, e);
            fail(certificateId, false, "예상하지 못한 오류: " + e.getMessage(), null);
            return Optional.of(e.getMessage());
        } finally {
            running.remove(certificateId);
        }
    }

    public boolean isRunning(long certificateId) {
        return running.contains(certificateId);
    }

    private Optional<String> run(long id) {
        Target target = tx.execute(status -> {
            TlsCertificate cert = load(id);
            return new Target(cert.getName(), cert.domainList(), cert.getSource(), cert.paths());
        });
        if (target.source() != CertificateSource.LETS_ENCRYPT) {
            return Optional.of("업로드한 인증서는 자동으로 갱신하지 않습니다.");
        }
        boolean renewal = target.paths() != null;
        X509Certificate replaces = renewal ? readQuietly(target.paths()) : null;

        AcmeClient.Issued issued;
        try {
            issued = acme.issue(target.domains(), replaces);
        } catch (AcmeClient.AcmeFailure e) {
            return Optional.of(fail(id, renewal, e.getMessage(), e.getRetryAfter()));
        }
        Instant notBefore = issued.leaf().getNotBefore().toInstant();
        Instant notAfter = issued.leaf().getNotAfter().toInstant();
        Instant nextRenewal = RenewalSchedule.next(notBefore, notAfter, issued.window());
        CertificatePaths fresh = certificateFiles.write(target.name(), issued.fullchainPem(), issued.privateKeyPem());
        String message = (renewal ? "인증서 갱신: " : "인증서 발급: ") + target.name();
        return install(id, target, fresh, notBefore, notAfter, nextRenewal,
                renewal ? VersionSource.CERT_RENEW : VersionSource.CERT_ISSUE, null, message);
    }

    /**
     * 새 인증서 파일을 쓰는 설정으로 바꿔 적용한다. 인증서를 쓰는 사이트가 없으면 인증서만 활성화한다.
     */
    Optional<String> install(long id, Target target, CertificatePaths fresh, Instant notBefore, Instant notAfter,
                             Instant nextRenewal, VersionSource source, String actor, String message) {
        Runnable activate = () -> load(id).activate(fresh, notBefore, notAfter, nextRenewal, clock.instant());
        ApplyResult result;
        try {
            result = applyWaiting(() -> tx.execute(status -> changesFor(id, target.paths(), fresh)),
                    new ApplyService.Origin(actor, message, source, null, activate));
        } catch (RuntimeException e) {
            certificateFiles.discard(fresh);
            return Optional.of(fail(id, source == VersionSource.CERT_RENEW, "설정에 반영하지 못했습니다: " + e.getMessage(), null));
        }
        if (result.nothingChanged()) {
            tx.executeWithoutResult(status -> activate.run());
        } else if (!result.succeeded()) {
            certificateFiles.discard(fresh);
            return Optional.of(fail(id, source == VersionSource.CERT_RENEW,
                    "새 인증서를 적용하지 못해 이전 설정으로 되돌렸습니다(버전 #" + result.versionId() + "). "
                            + Objects.toString(result.detail(), ""), null));
        }
        certificateFiles.prune(target.name(), fresh);
        log.info("{} applied ({})", message, fresh.certPath());
        return Optional.empty();
    }

    TlsCertificate load(long id) {
        return certificateRepository.findById(id).orElseThrow(() -> new NotFoundException("certificate " + id));
    }

    Target targetOf(TlsCertificate cert) {
        return new Target(cert.getName(), cert.domainList(), cert.getSource(), cert.paths());
    }

    /**
     * 인증서를 쓰는 설정 파일의 새 내용. 폼 사이트는 새 경로로 다시 만들고, 그 밖의 파일은 이전 경로를 새 경로로 바꾼다.
     * 사용자가 쓰던 초안도 같은 식으로 고쳐 둔다(초안은 적용하지 않는다).
     */
    private List<FileChange> changesFor(long id, CertificatePaths previous, CertificatePaths fresh) {
        Map<String, String> changes = new LinkedHashMap<>();
        for (Site site : siteRepository.findByCertificateId(id)) {
            if (site.getType() == SiteType.RAW || !site.isHttpsEnabled()) {
                continue;
            }
            ConfigFile file = site.getConfigFile();
            String content = renderer.render(site.getPrimaryDomain(), rendering.formOf(site), fresh);
            if (file.getDraftContent() != null) {
                file.stageContent(content);
            }
            // 아직 적용하지 않은 사이트 파일은 쓰지 않는다(사용자가 적용할 때 새 경로로 반영된다)
            if (store.read(file.getPath()) != null) {
                changes.put(file.getPath(), content);
            }
        }
        if (previous != null) {
            for (ConfigFile file : configFileRepository.findAllByOrderByPathAsc()) {
                if (changes.containsKey(file.getPath())) {
                    continue;
                }
                String disk = store.read(file.getPath());
                if (disk != null && references(disk, previous)) {
                    changes.put(file.getPath(), replace(disk, previous, fresh));
                }
                if (file.getDraftContent() != null && references(file.getDraftContent(), previous)) {
                    file.stageContent(replace(file.getDraftContent(), previous, fresh));
                }
            }
        }
        return changes.entrySet().stream()
                .filter(e -> !Objects.equals(e.getValue(), store.read(e.getKey())))
                .map(e -> new FileChange(e.getKey(), e.getValue()))
                .toList();
    }

    private static boolean references(String content, CertificatePaths paths) {
        return content.contains(paths.certPath()) || content.contains(paths.keyPath());
    }

    private static String replace(String content, CertificatePaths from, CertificatePaths to) {
        return content.replace(from.certPath(), to.certPath()).replace(from.keyPath(), to.keyPath());
    }

    /** 다른 적용이 끝날 때까지 기다렸다가 적용한다. 시스템 작업이라 사용자처럼 바로 거절하지 않는다. */
    private ApplyResult applyWaiting(Supplier<List<FileChange>> changes, ApplyService.Origin origin) {
        Instant deadline = clock.instant().plus(APPLY_WAIT);
        while (true) {
            try {
                return applyService.applyChanges(changes, origin);
            } catch (ApplyInProgressException e) {
                if (clock.instant().isAfter(deadline)) {
                    throw e;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private String fail(long id, boolean renewal, String error, Instant serverRetryAfter) {
        Instant now = clock.instant();
        TlsCertificate cert = tx.execute(status -> {
            TlsCertificate c = load(id);
            c.recordFailure(error, now, serverRetryAfter);
            return c;
        });
        String what = renewal ? "갱신" : "발급";
        log.warn("certificate {} {} failed: {}", cert.getName(), what, error);
        events.publishEvent(AuditEvent.failure(null, renewal ? AuditAction.CERT_RENEW : AuditAction.CERT_ISSUE,
                "cert:" + cert.getName(), error));
        String message = cert.getName() + " 인증서 " + what + "에 실패했습니다.\n\n" + error
                + (renewal && cert.getNotAfter() != null ? "\n\n지금 인증서는 " + cert.getNotAfter() + "까지 유효하며 계속 사용합니다." : "")
                + "\n다음 시도: " + cert.getRetryAfter();
        notifications.sendToAll(new NotificationService.Notice(renewal ? "CERT_RENEW_FAILED" : "CERT_ISSUE_FAILED",
                cert.getName() + " 인증서 " + what + " 실패", message));
        return error;
    }

    private X509Certificate readQuietly(CertificatePaths paths) {
        try {
            return certificateFiles.readLeaf(paths);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
