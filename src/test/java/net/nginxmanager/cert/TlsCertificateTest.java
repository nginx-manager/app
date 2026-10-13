package net.nginxmanager.cert;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TlsCertificateTest {

    static final Instant ISSUED = Instant.parse("2026-10-01T00:00:00Z");

    private static TlsCertificate active(Duration lifetime) {
        TlsCertificate cert = TlsCertificate.letsEncrypt("a.example.com", List.of("a.example.com"));
        cert.activate(new CertificatePaths("/c", "/k"), ISSUED, ISSUED.plus(lifetime), ISSUED, ISSUED);
        return cert;
    }

    @Test
    void ninetyDayCertificateWarnsThirtyDaysBeforeExpiry() {
        TlsCertificate cert = active(Duration.ofDays(90));

        assertThat(cert.isExpiringSoon(ISSUED.plus(Duration.ofDays(59)))).isFalse();
        assertThat(cert.isExpiringSoon(ISSUED.plus(Duration.ofDays(61)))).isTrue();
    }

    @Test
    void shortLivedCertificateWarnsOnlyInItsLastThird() {
        TlsCertificate cert = active(Duration.ofDays(6));

        assertThat(cert.isExpiringSoon(ISSUED.plus(Duration.ofDays(1)))).isFalse();
        assertThat(cert.isExpiringSoon(ISSUED.plus(Duration.ofDays(5)))).isTrue();
    }

    @Test
    void failuresBackOffExponentiallyUpToADay() {
        TlsCertificate cert = active(Duration.ofDays(90));
        Instant now = ISSUED;

        cert.recordFailure("x", now, null);
        assertThat(cert.getRetryAfter()).isEqualTo(now.plus(Duration.ofHours(1)));
        cert.recordFailure("x", now, null);
        assertThat(cert.getRetryAfter()).isEqualTo(now.plus(Duration.ofHours(2)));
        for (int i = 0; i < 10; i++) {
            cert.recordFailure("x", now, null);
        }
        assertThat(cert.getRetryAfter()).isEqualTo(now.plus(Duration.ofHours(24)));
        assertThat(cert.isDue(now.plus(Duration.ofHours(23)))).isFalse();
        assertThat(cert.isDue(now.plus(Duration.ofHours(25)))).isTrue();
    }
}
