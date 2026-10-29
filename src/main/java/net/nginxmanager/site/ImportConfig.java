package net.nginxmanager.site;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ImportProperties.class)
public class ImportConfig {
}
