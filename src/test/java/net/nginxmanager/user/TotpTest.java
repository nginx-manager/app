package net.nginxmanager.user;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TotpTest {

    /** RFC 6238 부록 B의 SHA-1 벡터(8자리). */
    @Test
    void matchesRfc6238Vectors() {
        byte[] key = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        assertThat(Totp.code(key, Totp.step(59), 8)).isEqualTo("94287082");
        assertThat(Totp.code(key, Totp.step(1111111109), 8)).isEqualTo("07081804");
        assertThat(Totp.code(key, Totp.step(1234567890), 8)).isEqualTo("89005924");
        assertThat(Totp.code(key, Totp.step(20000000000L), 8)).isEqualTo("65353130");
    }

    @Test
    void base32RoundTrips() {
        byte[] key = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        String text = Totp.base32(key);
        assertThat(text).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(Totp.base32Decode(text)).isEqualTo(key);
    }

    @Test
    void acceptsNeighbourStepsButNotReusedOnes() {
        String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
        long now = 1_700_000_000L;
        long step = Totp.step(now);

        assertThat(Totp.verify(secret, Totp.code(secret, step), now, null)).hasValue(step);
        assertThat(Totp.verify(secret, Totp.code(secret, step - 1), now, null)).hasValue(step - 1);
        assertThat(Totp.verify(secret, Totp.code(secret, step + 1), now, null)).hasValue(step + 1);
        assertThat(Totp.verify(secret, Totp.code(secret, step - 2), now, null)).isEmpty();
        // 이미 쓴 칸의 코드는 다시 받지 않는다
        assertThat(Totp.verify(secret, Totp.code(secret, step), now, step)).isEmpty();
        assertThat(Totp.verify(secret, "12 34 5", now, null)).isEmpty();
        assertThat(Totp.verify(secret, null, now, null)).isEmpty();
    }

    @Test
    void uriCarriesIssuerAndSecret() {
        assertThat(Totp.uri("nginx manager", "admin", "ABC"))
                .isEqualTo("otpauth://totp/nginx%20manager:admin?secret=ABC&issuer=nginx%20manager"
                        + "&algorithm=SHA1&digits=6&period=30");
        assertThat(QrCodes.svg("otpauth://totp/x")).startsWith("<svg").contains("<path");
    }
}
