package net.nginxmanager.admin;

import net.nginxmanager.apply.ApplyInProgressException;
import net.nginxmanager.apply.ApplyResult;
import net.nginxmanager.apply.ApplyService;
import net.nginxmanager.apply.FileChange;
import net.nginxmanager.cert.AcmeSettings;
import net.nginxmanager.cert.AcmeSettingsRepository;
import net.nginxmanager.cert.CertificatePaths;
import net.nginxmanager.cert.CertificateRegistry;
import net.nginxmanager.cert.CertificateService;
import net.nginxmanager.cert.TlsCertificate;
import net.nginxmanager.cert.TlsCertificateRepository;
import net.nginxmanager.config.ConfigFileStore;
import net.nginxmanager.config.VersionSource;
import net.nginxmanager.site.SiteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * 설치 설정(.env)의 관리 도메인으로 관리 화면 프록시 설정을 만들고 유지한다(008).
 * 인증서가 없으면 챌린지만 받는 설정을 두고 Let's Encrypt 발급을 맡긴다. 인증서가 생기면 HTTPS 프록시로 바꾼다.
 * 설정은 다른 변경과 같은 적용 절차(검증 → reload → 상태 확인 → 복구)를 거치고 버전으로 남는다.
 * 시작할 때와 1분마다 원하는 내용과 디스크를 맞춘다. 갱신 때도 이 확인이 새 인증서 경로로 바꾼다.
 */
@Service
public class AdminDomainService {

    private static final Logger log = LoggerFactory.getLogger(AdminDomainService.class);
    static final String ACTOR = "system";

    public enum State {
        /** 관리 도메인을 쓰지 않는다 */
        DISABLED,
        /** 인증서를 기다린다(발급 중이거나 실패해 다시 시도한다) */
        WAITING_CERTIFICATE,
        /** HTTPS로 열려 있다 */
        ACTIVE,
        /** 같은 도메인의 사이트가 있어 만들지 않았다 */
        CONFLICT,
        /** 설정을 적용하지 못했다 */
        FAILED
    }

    public record Status(State state, String domain, String detail) {
    }

    private final AdminDomainProperties properties;
    private final ConfigFileStore store;
    private final ApplyService applyService;
    private final CertificateRegistry certificateRegistry;
    private final TlsCertificateRepository certificateRepository;
    private final CertificateService certificateService;
    private final AcmeSettingsRepository acmeSettingsRepository;
    private final SiteRepository siteRepository;
    private final TransactionTemplate tx;
    private final Clock clock;

    private volatile Status status;

    public AdminDomainService(AdminDomainProperties properties, ConfigFileStore store, ApplyService applyService,
                              CertificateRegistry certificateRegistry, TlsCertificateRepository certificateRepository,
                              CertificateService certificateService, AcmeSettingsRepository acmeSettingsRepository,
                              SiteRepository siteRepository, TransactionTemplate tx, Clock clock) {
        this.properties = properties;
        this.store = store;
        this.applyService = applyService;
        this.certificateRegistry = certificateRegistry;
        this.certificateRepository = certificateRepository;
        this.certificateService = certificateService;
        this.acmeSettingsRepository = acmeSettingsRepository;
        this.siteRepository = siteRepository;
        this.tx = tx;
        this.clock = clock;
        this.status = new Status(properties.enabled() ? State.WAITING_CERTIFICATE : State.DISABLED,
                properties.domain(), null);
    }

    public Status status() {
        return status;
    }

    /** 관리 화면이 쓰는 도메인인지. 같은 도메인으로 사이트를 만들지 못하게 한다. */
    public boolean isReserved(String domain) {
        return properties.enabled() && domain != null && properties.domain().equalsIgnoreCase(domain.trim());
    }

    /** 관리도구가 만드는 파일인지. 원문 편집기에서 고치거나 지우지 못한다. */
    public static boolean isManagedPath(String path) {
        return AdminConfigRenderer.PATH.equals(path);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (properties.syncOnStart()) {
            sync();
        }
    }

    @Scheduled(initialDelayString = "${nginx-manager.admin.check-interval:PT1M}",
            fixedDelayString = "${nginx-manager.admin.check-interval:PT1M}")
    public void scheduled() {
        sync();
    }

    /** 원하는 설정과 디스크를 맞추고, 인증서가 필요하면 발급을 맡긴다. */
    public synchronized Status sync() {
        try {
            status = doSync();
        } catch (ApplyInProgressException e) {
            // 다른 적용이 끝나면 다음 확인에서 맞춘다
        } catch (RuntimeException e) {
            log.warn("admin domain sync failed", e);
            status = new Status(State.FAILED, properties.domain(), e.getMessage());
        }
        return status;
    }

    private Status doSync() {
        if (!properties.enabled()) {
            if (store.read(AdminConfigRenderer.PATH) != null) {
                apply(null, "관리 화면 도메인 해제");
            }
            return new Status(State.DISABLED, null, null);
        }
        String domain = properties.domain();
        if (siteRepository.existsByPrimaryDomain(domain)) {
            return new Status(State.CONFLICT, domain, null);
        }
        TlsCertificate cert = tx.execute(s -> {
            acmeSettingsRepository.findFirstByOrderByIdAsc()
                    .orElseGet(() -> acmeSettingsRepository.save(new AcmeSettings()))
                    .useContactEmailIfMissing(properties.email());
            long id = certificateRegistry.letsEncryptFor(domain, List.of(domain));
            return certificateRepository.findById(id).orElseThrow();
        });
        CertificatePaths paths = cert.paths();
        String desired = AdminConfigRenderer.render(properties, paths);
        if (!desired.equals(store.read(AdminConfigRenderer.PATH))) {
            ApplyResult result = apply(desired, paths == null
                    ? "관리 화면 도메인 준비: " + domain : "관리 화면 HTTPS: " + domain);
            if (!result.succeeded() && !result.nothingChanged()) {
                return new Status(State.FAILED, domain, Objects.toString(result.detail(), result.result().name()));
            }
        }
        if (cert.isDue(clock.instant()) && !certificateService.isRunning(cert.getId())) {
            // 발급되면 다음 확인 주기를 기다리지 않고 바로 HTTPS로 바꾼다
            certificateService.submit(cert.getId(), this::sync);
        }
        return paths == null
                ? new Status(State.WAITING_CERTIFICATE, domain, cert.getLastError())
                : new Status(State.ACTIVE, domain, null);
    }

    private ApplyResult apply(String content, String message) {
        return applyService.applyChanges(() -> List.of(new FileChange(AdminConfigRenderer.PATH, content)),
                new ApplyService.Origin(ACTOR, message, VersionSource.ADMIN_DOMAIN, null));
    }
}
