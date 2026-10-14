package net.nginxmanager.runtime;

import net.nginxmanager.runtime.ValidationResult.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NginxOutputParserTest {

    @Test
    void parsesErrorsWithFileAndLine() {
        String output = """
                nginx: [emerg] unknown directive "proxy_passs" in /etc/nginx/conf.d/sites/app.local.conf:7
                nginx: configuration file /etc/nginx/nginx.conf test failed
                """;

        List<ValidationMessage> messages = NginxOutputParser.parseTestOutput(output);

        assertThat(messages).containsExactly(new ValidationMessage(
                "emerg", "unknown directive \"proxy_passs\"", "/etc/nginx/conf.d/sites/app.local.conf", 7));
    }

    @Test
    void keepsMessagesWithoutLocation() {
        String output = """
                nginx: [warn] the "user" directive makes sense only if the master process runs with super-user privileges, ignored in /etc/nginx/nginx.conf:1
                nginx: [emerg] open() "/etc/nginx/missing.conf" failed (2: No such file or directory)
                """;

        List<ValidationMessage> messages = NginxOutputParser.parseTestOutput(output);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).level()).isEqualTo("warn");
        assertThat(messages.get(0).line()).isEqualTo(1);
        assertThat(messages.get(1)).isEqualTo(new ValidationMessage(
                "emerg", "open() \"/etc/nginx/missing.conf\" failed (2: No such file or directory)", null, null));
    }

    @Test
    void successfulTestHasNoMessages() {
        String output = """
                nginx: the configuration file /etc/nginx/nginx.conf syntax is ok
                nginx: configuration file /etc/nginx/nginx.conf test is successful
                """;

        assertThat(NginxOutputParser.parseTestOutput(output)).isEmpty();
    }

    @Test
    void parsesVersionAndModules() {
        String output = """
                nginx version: nginx/1.31.6
                built by gcc 13.3.0 (Ubuntu 13.3.0-6ubuntu2~24.04)
                built with OpenSSL 3.0.13 30 Jan 2024
                TLS SNI support enabled
                configure arguments: --prefix=/etc/nginx --with-cc-opt='-O2 -g' --with-compat --with-http_ssl_module --with-stream --with-http_xslt_module=dynamic --add-dynamic-module=/build/njs-1.0.1/nginx
                """;

        assertThat(NginxOutputParser.parseVersion(output)).isEqualTo("1.31.6");
        assertThat(NginxOutputParser.parseModules(output)).containsExactly(
                new NginxModule("compat", false),
                new NginxModule("http_ssl_module", false),
                new NginxModule("stream", false),
                new NginxModule("http_xslt_module", true),
                new NginxModule("njs-1.0.1", true));
    }
}
