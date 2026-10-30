package net.nginxmanager.logs;

import net.nginxmanager.common.NotFoundException;
import net.nginxmanager.config.ConfigFile;
import net.nginxmanager.config.ConfigFileStore;
import net.nginxmanager.runtime.NginxRuntimeProperties;
import net.nginxmanager.site.RawLogs;
import net.nginxmanager.site.Site;
import net.nginxmanager.site.SiteRepository;
import net.nginxmanager.site.SiteType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 사이트 로그 파일 위치. 폼 사이트는 설정이 쓰는 경로(sites/&lt;도메인&gt;.&lt;종류&gt;.log), 원문 사이트는 반영된 설정의
 * server 블록에 적힌 경로다(011). 어느 쪽이든 로그 루트 밖은 읽지 않는다.
 */
@Component
public class LogFiles {

    /** 지난 로그 파일 하나. index 0은 지금 파일. */
    public record LogFile(int index, Path path, String configPath, long size, LocalDateTime modified) {
    }

    private final Path logDir;
    private final Path sitesDir;
    private final int keep;
    private final SiteRepository siteRepository;
    private final ConfigFileStore store;

    public LogFiles(NginxRuntimeProperties properties, @Value("${nginx-manager.logs.keep:5}") int keep,
                    SiteRepository siteRepository, ConfigFileStore store) {
        this.logDir = properties.logDir().toAbsolutePath().normalize();
        this.sitesDir = logDir.resolve("sites");
        this.keep = Math.max(1, keep);
        this.siteRepository = siteRepository;
        this.store = store;
    }

    @Transactional(readOnly = true)
    public LogLocation locate(Site site, LogType type) {
        if (site.getType() != SiteType.RAW) {
            Path file = sitesDir.resolve(site.getPrimaryDomain() + "." + type.suffix() + ".log").normalize();
            // 도메인은 검증된 값이지만, 로그 디렉터리 밖을 읽지 않도록 한 번 더 막는다
            if (!file.getParent().equals(sitesDir)) {
                throw new IllegalArgumentException("log path outside log dir: " + file);
            }
            return new LogLocation(file, RawLogs.sitePath(site.getPrimaryDomain(), type.suffix()),
                    RawLogs.Status.FILE, false, false);
        }
        ConfigFile config = siteRepository.findById(site.getId())
                .orElseThrow(() -> new NotFoundException("site " + site.getId())).getConfigFile();
        String disk = store.read(config.getPath());
        RawLogs.Found found = disk == null ? new RawLogs.Found(RawLogs.Status.NOT_SET, null)
                : RawLogs.find(disk, type.directive());
        Path file = found.status() == RawLogs.Status.FILE ? toLocal(found.path()) : null;
        RawLogs.Status status = found.status() == RawLogs.Status.FILE && file == null ? RawLogs.Status.OUTSIDE
                : found.status();
        boolean pending = false;
        String configPath = found.path();
        if (file == null && config.getDraftContent() != null) {
            RawLogs.Found draft = RawLogs.find(config.getDraftContent(), type.directive());
            if (draft.status() == RawLogs.Status.FILE && toLocal(draft.path()) != null) {
                pending = true;
                configPath = draft.path();
            }
        }
        return new LogLocation(file, configPath, status, true, pending);
    }

    /** 지금 파일과, 크기를 넘어 밀려난 &lt;파일&gt;.1 … .keep 중 있는 것. */
    public List<LogFile> history(LogLocation location) throws IOException {
        List<LogFile> files = new ArrayList<>();
        if (!location.readable()) {
            return files;
        }
        Path current = location.file();
        files.add(info(0, current, location.configPath()));
        for (int i = 1; i <= keep; i++) {
            Path older = current.resolveSibling(current.getFileName() + "." + i);
            if (Files.isRegularFile(older)) {
                files.add(info(i, older, location.configPath() + "." + i));
            }
        }
        return files;
    }

    private static LogFile info(int index, Path path, String configPath) throws IOException {
        boolean exists = Files.isRegularFile(path);
        return new LogFile(index, path, configPath, exists ? Files.size(path) : 0,
                exists ? LocalDateTime.ofInstant(Files.getLastModifiedTime(path).toInstant(), ZoneId.systemDefault()) : null);
    }

    /** 설정에 적힌 /var/log/nginx/… 경로를 관리도구가 보는 로그 루트 아래 경로로. 루트 밖이면 null. */
    private Path toLocal(String nginxPath) {
        Path file = logDir.resolve(nginxPath.substring(RawLogs.NGINX_LOG_DIR.length())).normalize();
        return file.startsWith(logDir) && !file.equals(logDir) ? file : null;
    }
}
