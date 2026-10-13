package net.nginxmanager.runtime;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(NginxRuntimeProperties.class)
public class RuntimeConfig {

    @Bean
    public NginxRuntime nginxRuntime(NginxRuntimeProperties properties) {
        return new LocalNginxRuntime(properties, new CommandRunner());
    }
}
