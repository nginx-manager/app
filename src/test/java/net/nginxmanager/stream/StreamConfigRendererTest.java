package net.nginxmanager.stream;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 스트림 프록시 설정 생성과 폼 검증(spec 002 US1). */
class StreamConfigRendererTest {

    final StreamConfigRenderer renderer = new StreamConfigRenderer();
    final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    static StreamForm tcp(int port, String backends) {
        return new StreamForm(StreamProtocol.TCP, port, backends, null, null, null, null, null);
    }

    Set<String> violations(StreamForm form) {
        return validator.validate(form).stream().map(ConstraintViolation::getPropertyPath).map(Object::toString)
                .collect(Collectors.toSet());
    }

    @Test
    void tcpWithDefaults() {
        assertThat(renderer.render(tcp(5432, "10.0.0.5:5432"))).isEqualTo(StreamConfigRenderer.HEADER + """
                upstream stream_tcp_5432 {
                    server 10.0.0.5:5432;
                }

                server {
                    listen 5432;
                    proxy_pass stream_tcp_5432;
                }
                """);
    }

    @Test
    void udpWithBalancingTimeoutsAndNote() {
        String config = renderer.render(new StreamForm(StreamProtocol.UDP, 53, "10.0.0.2:53, 10.0.0.3:53\n[2001:db8::1]:53",
                StreamBalancing.IP_HASH, 5, 30, "  내부\nDNS  ", null));

        assertThat(config).contains("# 내부 DNS\n", "    hash $remote_addr consistent;\n",
                "    server 10.0.0.2:53;\n    server 10.0.0.3:53;\n    server [2001:db8::1]:53;\n",
                "    listen 53 udp;\n", "    proxy_connect_timeout 5s;\n", "    proxy_timeout 30s;\n");
        assertThat(renderer.render(new StreamForm(StreamProtocol.TCP, 3306, "db:3306", StreamBalancing.LEAST_CONN,
                null, null, null, null))).contains("    least_conn;\n");
    }

    @Test
    void backendValidation() {
        assertThat(violations(tcp(5432, "db.internal:5432 10.0.0.5:5432 [::1]:5432"))).isEmpty();
        assertThat(violations(tcp(5432, ""))).contains("backendsValid");
        assertThat(violations(tcp(5432, "10.0.0.5"))).contains("backendsValid");
        assertThat(violations(tcp(5432, "10.0.0.5:70000"))).contains("backendsValid");
        assertThat(violations(tcp(5432, "10.0.0.5:5432; proxy_pass x"))).contains("backendsValid");
        assertThat(violations(tcp(5432, "unix:/tmp/x.sock"))).contains("backendsValid");
    }

    @Test
    void managementPortsAndHttpPortsAreRefused() {
        assertThat(violations(tcp(5432, "127.0.0.1:7070"))).contains("backendsNotManager");
        assertThat(violations(tcp(5432, "manager:9000"))).contains("backendsNotManager");
        assertThat(violations(tcp(80, "10.0.0.5:80"))).contains("listenPortFree");
        assertThat(violations(tcp(9000, "10.0.0.5:80"))).contains("listenPortFree");
        assertThat(violations(new StreamForm(StreamProtocol.UDP, 443, "10.0.0.5:443", null, null, null, null, null)))
                .contains("listenPortFree");
        assertThat(violations(new StreamForm(StreamProtocol.UDP, 80, "10.0.0.5:80", null, null, null, null, null)))
                .isEmpty();
        assertThat(violations(tcp(0, "10.0.0.5:80"))).contains("listenPort");
    }

    @Test
    void pathsRoundTrip() {
        assertThat(StreamPaths.of(StreamProtocol.UDP, 53)).isEqualTo("stream.d/udp-53.conf");
        assertThat(StreamPaths.keyOf("stream.d/tcp-5432.conf")).isEqualTo(new StreamPaths.Key(StreamProtocol.TCP, 5432));
        assertThat(StreamPaths.keyOf("stream.d/custom.conf")).isNull();
        assertThat(StreamPaths.keyOf("conf.d/tcp-5432.conf")).isNull();
    }
}
