package net.nginxmanager.cert;

import net.nginxmanager.admin.AdminDomainProperties;
import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.audit.AuditEvent;
import net.nginxmanager.common.NotFoundException;
import net.nginxmanager.site.Site;
import net.nginxmanager.site.SiteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 발급하지 못한(대기·실패) 인증서를 지운다(010). 지우면 자동 재시도도 멈춘다.
 * 사이트나 관리 도메인이 아직 쓰는 인증서는 지우지 않는다. 지워도 다시 만들어져 재시도가 이어지기 때문이다.
 */
@Service
public class CertificateDeletion {

    private static final Logger log = LoggerFactory.getLogger(CertificateDeletion.class);

    /** 지울 수 없는 이유. */
    public enum Blocker {
        /** 발급된 인증서. 설정이 파일을 가리키고 있을 수 있다 */
        ISSUED,
        /** 지금 발급 중 */
        RUNNING,
        /** 사이트가 쓰고 있다 */
        IN_USE,
        /** 관리 화면 도메인(.env)의 인증서 */
        ADMIN_DOMAIN
    }

    public static class BlockedException extends RuntimeException {
        private final transient Blocker blocker;
        private final transient List<String> sites;

        BlockedException(Blocker blocker, List<String> sites) {
            super(blocker.name());
            this.blocker = blocker;
            this.sites = sites;
        }

        public Blocker blocker() {
            return blocker;
        }

        /** IN_USE일 때 그 인증서를 쓰는 사이트 */
        public List<String> sites() {
            return sites;
        }
    }

    private final TlsCertificateRepository certificateRepository;
    private final SiteRepository siteRepository;
    private final CertificateService certificateService;
    private final CertificateFiles certificateFiles;
    private final AdminDomainProperties adminDomain;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;

    public CertificateDeletion(TlsCertificateRepository certificateRepository, SiteRepository siteRepository,
                               CertificateService certificateService, CertificateFiles certificateFiles,
                               AdminDomainProperties adminDomain, TransactionTemplate tx,
                               ApplicationEventPublisher events) {
        this.certificateRepository = certificateRepository;
        this.siteRepository = siteRepository;
        this.certificateService = certificateService;
        this.certificateFiles = certificateFiles;
        this.adminDomain = adminDomain;
        this.tx = tx;
        this.events = events;
    }

    /**
     * 지울 수 없는 이유. 지금 발급 중인지는 보지 않는다(화면은 발급 중 표시를 따로 한다).
     *
     * @param sites 모든 사이트. 목록 화면에서 한 번 읽어 여러 인증서에 쓴다
     */
    public Optional<Blocker> blocker(TlsCertificate cert, Collection<Site> sites) {
        if (cert.getStatus() != CertificateStatus.PENDING) {
            return Optional.of(Blocker.ISSUED);
        }
        if (adminDomain.enabled() && adminDomain.domain().equalsIgnoreCase(cert.getName())) {
            return Optional.of(Blocker.ADMIN_DOMAIN);
        }
        if (!usersOf(cert, sites).isEmpty()) {
            return Optional.of(Blocker.IN_USE);
        }
        return Optional.empty();
    }

    /** 이 인증서를 고른 사이트(저장한 폼 값이나 마지막으로 반영된 값에서). */
    public static List<String> usersOf(TlsCertificate cert, Collection<Site> sites) {
        return sites.stream()
                .filter(s -> Objects.equals(cert.getId(), s.getCertificateId())
                        || Objects.equals(cert.getId(), s.getAppliedCertificateId()))
                .map(Site::getPrimaryDomain)
                .sorted()
                .toList();
    }

    /**
     * 인증서 기록과 남은 파일을 지운다.
     *
     * @return 지운 인증서 이름
     * @throws BlockedException 지울 수 없을 때
     */
    public String delete(long id, String actor) {
        if (!certificateRepository.existsById(id)) {
            throw new NotFoundException("certificate " + id);
        }
        if (!certificateService.hold(id)) {
            throw new BlockedException(Blocker.RUNNING, List.of());
        }
        try {
            String name = tx.execute(status -> {
                TlsCertificate cert = certificateRepository.findById(id)
                        .orElseThrow(() -> new NotFoundException("certificate " + id));
                List<Site> sites = siteRepository.findAll();
                Optional<Blocker> blocker = blocker(cert, sites);
                if (blocker.isPresent()) {
                    throw new BlockedException(blocker.get(), usersOf(cert, sites));
                }
                certificateRepository.delete(cert);
                return cert.getName();
            });
            // 이름이 같은 디렉터리를 쓰는 다른 인증서가 있으면(대소문자·특수문자만 다른 이름) 파일은 그대로 둔다
            String dir = CertificateFiles.safeName(name);
            boolean shared = certificateRepository.findAll().stream()
                    .anyMatch(c -> CertificateFiles.safeName(c.getName()).equals(dir));
            if (!shared) {
                certificateFiles.removeAll(name, certificateService::referenced);
            }
            events.publishEvent(AuditEvent.success(actor, AuditAction.CERT_DELETE, "cert:" + name));
            log.info("certificate {} deleted by {}", name, actor);
            return name;
        } finally {
            certificateService.release(id);
        }
    }
}
