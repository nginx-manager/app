package net.nginxmanager.stream;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 스트림 접근 제한과 PROXY protocol(025). */
class StreamAccessTest {

    final StreamConfigRenderer renderer = new StreamConfigRenderer();
    final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static StreamForm form(StreamProtocol protocol, String allow, String deny, boolean proxyProtocol) {
        return new StreamForm(protocol, 5432, "10.0.0.5:5432", null, null, null, null, null, allow, deny,
                proxyProtocol);
    }

    @Test
    void denyComesFirstThenAllowThenDenyAll() {
        StreamForm form = form(StreamProtocol.TCP, "10.0.0.0/8, 192.168.0.0/16", "10.0.0.66", true);
        assertThat(validator.validate(form)).isEmpty();
        assertThat(renderer.render(form)).contains("""
                    proxy_pass stream_tcp_5432;
                    deny 10.0.0.66;
                    allow 10.0.0.0/8;
                    allow 192.168.0.0/16;
                    deny all;
                    proxy_protocol on;
                """);
    }

    @Test
    void denyOnlyKeepsEveryoneElseAndUdpNeverUsesProxyProtocol() {
        String conf = renderer.render(form(StreamProtocol.UDP, null, "203.0.113.7", true));
        assertThat(conf).contains("deny 203.0.113.7;").doesNotContain("deny all", "proxy_protocol");
        assertThat(renderer.render(form(StreamProtocol.TCP, null, null, false)))
                .doesNotContain("allow", "deny", "proxy_protocol");
    }

    @Test
    void invalidAddressesAreRejected() {
        assertThat(form(StreamProtocol.TCP, "not-an-ip", null, false).isAllowFromValid()).isFalse();
        assertThat(form(StreamProtocol.TCP, null, "10.0.0.0/33", false).isDenyFromValid()).isFalse();
        assertThat(form(StreamProtocol.TCP, "2001:db8::/32", "::1", false).isAllowFromValid()).isTrue();
    }

    @Test
    void appliedSettingsRestoreTheAccessRules() {
        StreamProxy stream = new StreamProxy(StreamProtocol.TCP, 5432);
        stream.applyForm(form(StreamProtocol.TCP, "10.0.0.0/8", "10.0.0.66", true));
        stream.markApplied();
        stream.applyForm(form(StreamProtocol.TCP, null, null, false));
        assertThat(stream.isAccessRestricted()).isFalse();

        assertThat(stream.restoreApplied()).isTrue();
        StreamForm restored = stream.toForm();
        assertThat(restored.allowFrom()).isEqualTo("10.0.0.0/8");
        assertThat(restored.denyFrom()).isEqualTo("10.0.0.66");
        assertThat(restored.proxyProtocol()).isTrue();
    }
}
