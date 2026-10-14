package net.nginxmanager.runtime;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component("nginx")
public class NginxHealthIndicator implements HealthIndicator {

    private final NginxRuntime runtime;

    public NginxHealthIndicator(NginxRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Health health() {
        HealthResult result = runtime.healthCheck(Duration.ZERO);
        return (result.healthy() ? Health.up() : Health.down()).withDetail("detail", result.detail()).build();
    }
}
