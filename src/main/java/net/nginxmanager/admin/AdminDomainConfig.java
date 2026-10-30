package net.nginxmanager.admin;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AdminDomainProperties.class)
public class AdminDomainConfig {
}
