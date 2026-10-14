package net.nginxmanager.runtime;

import net.nginxmanager.common.AppVersion;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableConfigurationProperties(NginxRuntimeProperties.class)
public class RuntimeConfig {

    @Bean
    public NginxRuntime nginxRuntime(NginxRuntimeProperties properties, JsonMapper json, AppVersion appVersion) {
        return new AgentNginxRuntime(properties, json, appVersion.current());
    }
}
