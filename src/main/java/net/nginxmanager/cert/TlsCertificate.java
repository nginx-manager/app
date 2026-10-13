package net.nginxmanager.cert;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import net.nginxmanager.common.BaseEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * 사이트에 쓰는 TLS 인증서. 키 내용은 DB에 두지 않고 파일 경로만 둔다.
 * 파일은 발급·갱신마다 새 디렉터리에 쓰고, 설정의 경로를 바꾸는 적용으로 반영한다(실패하면 이전 경로로 복구).
 */
@Entity
@Table(name = "certificate")
public class TlsCertificate extends BaseEntity {

    static final Duration EXPIRY_WARNING = Duration.ofDays(30);

    /** Let's Encrypt는 대표 도메인, 업로드는 사용자가 붙인 이름. 파일 디렉터리 이름으로도 쓴다. */
    @Column(nullable = false, unique = true, length = 253)
    private String name;

    /** 공백으로 구분한 도메인 목록. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String domains;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CertificateSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CertificateStatus status;

    @Column(name = "cert_path", length = 500)
    private String certPath;

    @Column(name = "key_path", length = 500)
    private String keyPath;

    @Column(name = "not_before")
    private Instant notBefore;

    @Column(name = "not_after")
    private Instant notAfter;

    @Column(name = "auto_renew", nullable = false)
    private boolean autoRenew;

    @Column(name = "next_renewal_at")
    private Instant nextRenewalAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    @Column(name = "retry_after")
    private Instant retryAfter;

    @Column(name = "last_expiry_notice_at")
    private Instant lastExpiryNoticeAt;

    protected TlsCertificate() {
    }

    public static TlsCertificate letsEncrypt(String primaryDomain, List<String> domains) {
        TlsCertificate cert = new TlsCertificate();
        cert.name = primaryDomain;
        cert.domains = String.join(" ", domains);
        cert.source = CertificateSource.LETS_ENCRYPT;
        cert.status = CertificateStatus.PENDING;
        cert.autoRenew = true;
        return cert;
    }

    public static TlsCertificate uploaded(String name, List<String> domains) {
        TlsCertificate cert = new TlsCertificate();
        cert.name = name;
        cert.domains = String.join(" ", domains);
        cert.source = CertificateSource.UPLOADED;
        cert.status = CertificateStatus.PENDING;
        cert.autoRenew = false;
        return cert;
    }

    /** 새 인증서 파일이 설정에 반영된 뒤 호출한다. */
    void activate(CertificatePaths paths, Instant notBefore, Instant notAfter, Instant nextRenewalAt, Instant now) {
        this.certPath = paths.certPath();
        this.keyPath = paths.keyPath();
        this.notBefore = notBefore;
        this.notAfter = notAfter;
        this.nextRenewalAt = nextRenewalAt;
        this.status = CertificateStatus.ACTIVE;
        this.lastAttemptAt = now;
        this.lastError = null;
        this.failureCount = 0;
        this.retryAfter = null;
    }

    /** 실패를 기록하고 다음 시도 시각을 정한다. 지수 백오프(1시간부터 최대 24시간), 서버가 알려준 시각이 있으면 그 이후. */
    void recordFailure(String error, Instant now, Instant serverRetryAfter) {
        this.lastAttemptAt = now;
        this.lastError = error;
        this.failureCount++;
        Duration backoff = Duration.ofHours(Math.min(24, 1L << Math.min(failureCount - 1, 5)));
        Instant next = now.plus(backoff);
        this.retryAfter = serverRetryAfter != null && serverRetryAfter.isAfter(next) ? serverRetryAfter : next;
    }

    /** 도메인이 바뀌면 지금 인증서로는 안 되므로 바로 다시 발급하게 한다. */
    void requestReissue(List<String> domains, Instant now) {
        this.domains = String.join(" ", domains);
        this.nextRenewalAt = now;
        this.retryAfter = null;
    }

    void markExpiryNotified(Instant now) {
        this.lastExpiryNoticeAt = now;
    }

    public CertificatePaths paths() {
        return status == CertificateStatus.ACTIVE ? new CertificatePaths(certPath, keyPath) : null;
    }

    public List<String> domainList() {
        return Arrays.stream(domains.split(" ")).filter(d -> !d.isBlank()).toList();
    }

    public boolean isExpiringSoon(Instant now) {
        return notAfter != null && now.plus(EXPIRY_WARNING).isAfter(notAfter);
    }

    public boolean isFailing() {
        return failureCount > 0;
    }

    public boolean isDue(Instant now) {
        if (!autoRenew || source != CertificateSource.LETS_ENCRYPT) {
            return false;
        }
        if (retryAfter != null && retryAfter.isAfter(now)) {
            return false;
        }
        return status == CertificateStatus.PENDING || (nextRenewalAt != null && !nextRenewalAt.isAfter(now));
    }

    public long daysLeft(Instant now) {
        return notAfter == null ? -1 : Duration.between(now, notAfter).toDays();
    }

    public String getName() {
        return name;
    }

    public String getDomains() {
        return domains;
    }

    public CertificateSource getSource() {
        return source;
    }

    public CertificateStatus getStatus() {
        return status;
    }

    public String getCertPath() {
        return certPath;
    }

    public String getKeyPath() {
        return keyPath;
    }

    public Instant getNotBefore() {
        return notBefore;
    }

    public Instant getNotAfter() {
        return notAfter;
    }

    public boolean isAutoRenew() {
        return autoRenew;
    }

    public Instant getNextRenewalAt() {
        return nextRenewalAt;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public Instant getRetryAfter() {
        return retryAfter;
    }

    public Instant getLastExpiryNoticeAt() {
        return lastExpiryNoticeAt;
    }
}
