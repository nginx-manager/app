package net.nginxmanager.logs;

import net.nginxmanager.runtime.NginxRuntimeProperties;
import net.nginxmanager.site.Site;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** 사이트 로그 파일 위치. 폼 사이트 설정이 쓰는 경로(sites/&lt;도메인&gt;.&lt;종류&gt;.log)와 같다. */
@Component
public class LogFiles {

    private final Path sitesDir;

    public LogFiles(NginxRuntimeProperties properties) {
        this.sitesDir = properties.logDir().resolve("sites").toAbsolutePath().normalize();
    }

    public Path of(Site site, LogType type) {
        Path file = sitesDir.resolve(site.getPrimaryDomain() + "." + type.suffix() + ".log").normalize();
        // 도메인은 검증된 값이지만, 로그 디렉터리 밖을 읽지 않도록 한 번 더 막는다
        if (!file.getParent().equals(sitesDir)) {
            throw new IllegalArgumentException("log path outside log dir: " + file);
        }
        return file;
    }
}
