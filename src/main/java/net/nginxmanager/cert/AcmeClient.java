package net.nginxmanager.cert;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** ACME(Let's Encrypt) 발급. 테스트에서 바꿔 끼울 수 있게 인터페이스로 둔다. */
public interface AcmeClient {

    /**
     * HTTP-01 챌린지로 인증서를 발급한다. 이미 쓰던 인증서가 있으면 replaces로 알려 ARI 갱신으로 처리되게 한다.
     */
    Issued issue(List<String> domains, X509Certificate replaces) throws AcmeFailure;

    /** 발급된 인증서와 새 키(PEM). */
    record Issued(String fullchainPem, String privateKeyPem, X509Certificate leaf, Optional<RenewalWindow> window) {
    }

    /** ACME 서버가 권하는 갱신 기간(ARI). */
    record RenewalWindow(Instant start, Instant end) {
    }

    /** 발급 실패. */
    class AcmeFailure extends Exception {

        /** 서버가 다시 시도하라고 알려 준 시각(발급 한도 등). 없으면 null. */
        private final transient Instant retryAfter;

        public AcmeFailure(String message, Instant retryAfter, Throwable cause) {
            super(message, cause);
            this.retryAfter = retryAfter;
        }

        public Instant getRetryAfter() {
            return retryAfter;
        }
    }
}
