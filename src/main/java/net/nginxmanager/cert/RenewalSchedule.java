package net.nginxmanager.cert;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 다음 갱신 시각(FR-027). ACME 서버가 갱신 기간(ARI)을 알려 주면 그 기간의 가운데, 없으면 남은 유효기간이
 * 1/3이 되는 때. 유효기간이 짧아져도 비율로 계산하므로 설정을 바꿀 필요가 없다.
 */
public final class RenewalSchedule {

    private RenewalSchedule() {
    }

    public static Instant next(Instant notBefore, Instant notAfter, Optional<AcmeClient.RenewalWindow> window) {
        if (window.isPresent() && window.get().end().isAfter(window.get().start())) {
            Instant start = window.get().start();
            return start.plus(Duration.between(start, window.get().end()).dividedBy(2));
        }
        Duration lifetime = Duration.between(notBefore, notAfter);
        return notAfter.minus(lifetime.dividedBy(3));
    }
}
