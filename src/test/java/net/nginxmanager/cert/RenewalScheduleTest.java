package net.nginxmanager.cert;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RenewalScheduleTest {

    static final Instant NOT_BEFORE = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    void withoutRenewalInfoRenewsWhenOneThirdOfLifetimeRemains() {
        Instant notAfter = NOT_BEFORE.plus(Duration.ofDays(90));

        assertThat(RenewalSchedule.next(NOT_BEFORE, notAfter, Optional.empty()))
                .isEqualTo(NOT_BEFORE.plus(Duration.ofDays(60)));
    }

    @Test
    void shorterLifetimesScaleWithoutConfiguration() {
        Instant notAfter = NOT_BEFORE.plus(Duration.ofDays(6));

        assertThat(RenewalSchedule.next(NOT_BEFORE, notAfter, Optional.empty()))
                .isEqualTo(NOT_BEFORE.plus(Duration.ofDays(4)));
    }

    @Test
    void renewalInfoWindowWinsAndPicksItsMiddle() {
        Instant start = NOT_BEFORE.plus(Duration.ofDays(50));
        Instant end = start.plus(Duration.ofDays(2));

        assertThat(RenewalSchedule.next(NOT_BEFORE, NOT_BEFORE.plus(Duration.ofDays(90)),
                Optional.of(new AcmeClient.RenewalWindow(start, end))))
                .isEqualTo(start.plus(Duration.ofDays(1)));
    }

    @Test
    void invalidWindowFallsBackToLifetime() {
        Instant notAfter = NOT_BEFORE.plus(Duration.ofDays(90));
        Instant start = NOT_BEFORE.plus(Duration.ofDays(50));

        assertThat(RenewalSchedule.next(NOT_BEFORE, notAfter,
                Optional.of(new AcmeClient.RenewalWindow(start, start))))
                .isEqualTo(NOT_BEFORE.plus(Duration.ofDays(60)));
    }
}
