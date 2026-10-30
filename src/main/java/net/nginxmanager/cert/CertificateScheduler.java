package net.nginxmanager.cert;

import net.nginxmanager.notify.NotificationService;
import net.nginxmanager.site.Site;
import net.nginxmanager.site.SiteRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 6시간마다(시작 직후 한 번 포함) 갱신할 인증서를 처리하고, 만료가 30일 안으로 다가온 인증서를 알린다(7일에 한 번).
 */
@Component
public class CertificateScheduler {

    static final Duration EXPIRY_NOTICE_INTERVAL = Duration.ofDays(7);

    private final TlsCertificateRepository certificateRepository;
    private final SiteRepository siteRepository;
    private final CertificateService certificateService;
    private final NotificationService notifications;
    private final TransactionTemplate tx;
    private final Clock clock;

    public CertificateScheduler(TlsCertificateRepository certificateRepository, SiteRepository siteRepository,
                                CertificateService certificateService, NotificationService notifications,
                                TransactionTemplate tx, Clock clock) {
        this.certificateRepository = certificateRepository;
        this.siteRepository = siteRepository;
        this.certificateService = certificateService;
        this.notifications = notifications;
        this.tx = tx;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${nginx-manager.cert.initial-delay:PT30S}",
            fixedDelayString = "${nginx-manager.cert.check-interval:PT6H}")
    public void run() {
        renewDue();
        notifyExpiring();
    }

    /** 사이트가 HTTPS로 쓰고 있는 Let's Encrypt 인증서 중 갱신(또는 첫 발급) 때가 된 것. */
    public void renewDue() {
        Instant now = clock.instant();
        List<Long> due = tx.execute(status -> {
            Set<Long> inUse = inUse();
            return certificateRepository.findBySourceOrderByNameAsc(CertificateSource.LETS_ENCRYPT).stream()
                    .filter(c -> inUse.contains(c.getId()) && c.isDue(now))
                    .map(TlsCertificate::getId)
                    .toList();
        });
        due.forEach(certificateService::issueOrRenew);
    }

    /**
     * 켜진 사이트가 HTTPS로 쓰는 인증서. 쓰지 않는 인증서는 갱신하지도, 만료를 알리지도 않는다. 꺼 둔 사이트는 nginx가
     * 서비스하지 않아 도메인 확인이 실패하므로 뺀다(013). 다시 켜서 적용하면 필요할 때 발급한다.
     */
    private Set<Long> inUse() {
        return siteRepository.findByCertificateIdIsNotNull().stream()
                .filter(Site::isHttpsEnabled)
                .filter(Site::isEnabled)
                .map(Site::getCertificateId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    public void notifyExpiring() {
        Instant now = clock.instant();
        List<TlsCertificate> expiring = tx.execute(status -> {
            Set<Long> inUse = inUse();
            List<TlsCertificate> list = certificateRepository.findAllByOrderByNameAsc().stream()
                    .filter(c -> inUse.contains(c.getId()))
                    .filter(c -> c.getStatus() == CertificateStatus.ACTIVE && c.isExpiringSoon(now))
                    .filter(c -> c.getLastExpiryNoticeAt() == null
                            || c.getLastExpiryNoticeAt().plus(EXPIRY_NOTICE_INTERVAL).isBefore(now))
                    .toList();
            list.forEach(c -> c.markExpiryNotified(now));
            return list;
        });
        for (TlsCertificate cert : expiring) {
            notifications.sendToAll(new NotificationService.Notice("CERT_EXPIRING",
                    cert.getName() + " 인증서 만료 " + cert.daysLeft(now) + "일 전",
                    cert.getName() + " 인증서가 " + cert.getNotAfter() + "에 만료됩니다."
                            + (cert.isAutoRenew() ? " 자동 갱신이 아직 성공하지 못했습니다. 마지막 오류: "
                            + Objects.toString(cert.getLastError(), "없음")
                            : " 업로드한 인증서라 직접 교체해야 합니다.")));
        }
    }
}
