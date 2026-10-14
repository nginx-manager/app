package net.nginxmanager.startup;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/** @param checkDir init-config-check가 결과를 쓰는 곳 */
@ConfigurationProperties("nginx-manager.startup")
public record StartupProperties(Path checkDir) {

    public StartupProperties {
        if (checkDir == null) {
            checkDir = Path.of("/run/nginx-manager/startup-check");
        }
    }
}
