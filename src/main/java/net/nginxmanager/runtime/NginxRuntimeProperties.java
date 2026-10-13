package net.nginxmanager.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties("nginx.runtime")
public record NginxRuntimeProperties(
        Path binary,
        Path configRoot,
        Path pidFile,
        URI statusUrl,
        Duration commandTimeout,
        Duration healthTimeout) {
}
