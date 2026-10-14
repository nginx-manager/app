package net.nginxmanager.dashboard;

import net.nginxmanager.cert.CertificateStatus;
import net.nginxmanager.cert.TlsCertificate;
import net.nginxmanager.cert.TlsCertificateRepository;
import net.nginxmanager.history.VersionQueryRepository;
import net.nginxmanager.runtime.NginxRuntime;
import net.nginxmanager.runtime.RuntimeStatus;
import net.nginxmanager.site.SiteRepository;
import net.nginxmanager.startup.StartupCheck;
import net.nginxmanager.startup.StartupCheckService;
import net.nginxmanager.startup.VersionChange;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** 대시보드에 보이는 값(FR-014, FR-013, FR-023). */
@Service
public class DashboardService {

    static final int RECENT_VERSIONS = 5;

    /** 만료가 가깝거나 발급·갱신에 실패한 인증서. */
    public record CertificateWarning(long id, String name, Instant notAfter, long daysLeft, boolean failing,
                                     String lastError) {
    }

    public record Dashboard(RuntimeStatus status, long siteCount, long enabledSiteCount,
                            List<VersionQueryRepository.VersionRow> recentVersions,
                            List<CertificateWarning> certificateWarnings, long databaseBytes,
                            StartupCheck failedStartupCheck, VersionChange versionChange, String appVersion) {
    }

    private final NginxRuntime runtime;
    private final SiteRepository siteRepository;
    private final VersionQueryRepository versionQueryRepository;
    private final TlsCertificateRepository certificateRepository;
    private final JdbcClient jdbc;
    private final StartupCheckService startupCheckService;
    private final Clock clock;

    public DashboardService(NginxRuntime runtime, SiteRepository siteRepository,
                            VersionQueryRepository versionQueryRepository,
                            TlsCertificateRepository certificateRepository, JdbcClient jdbc,
                            StartupCheckService startupCheckService, Clock clock) {
        this.runtime = runtime;
        this.siteRepository = siteRepository;
        this.versionQueryRepository = versionQueryRepository;
        this.certificateRepository = certificateRepository;
        this.jdbc = jdbc;
        this.startupCheckService = startupCheckService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Dashboard load() {
        Instant now = clock.instant();
        List<CertificateWarning> warnings = certificateRepository.findAllByOrderByNameAsc().stream()
                .filter(c -> c.isFailing() || (c.getStatus() == CertificateStatus.ACTIVE && c.isExpiringSoon(now)))
                .map(c -> warning(c, now))
                .toList();
        RuntimeStatus status = runtime.status();
        // 시작 검사 실패는 nginx가 아직 내려가 있는 동안만 보여 준다(다시 띄웠으면 해결된 것)
        StartupCheck failed = startupCheckService.check()
                .filter(check -> !check.ok() && status.state() != RuntimeStatus.State.RUNNING)
                .orElse(null);
        return new Dashboard(status, siteRepository.count(), siteRepository.countByEnabledTrue(),
                versionQueryRepository.findPage(0, RECENT_VERSIONS), warnings, databaseBytes(), failed,
                startupCheckService.versionChange().orElse(null), startupCheckService.appVersion());
    }

    private static CertificateWarning warning(TlsCertificate c, Instant now) {
        return new CertificateWarning(c.getId(), c.getName(), c.getNotAfter(), c.daysLeft(now), c.isFailing(),
                c.getLastError());
    }

    /** SQLite 파일 크기(페이지 수 × 페이지 크기). */
    private long databaseBytes() {
        Long pages = jdbc.sql("PRAGMA page_count").query(Long.class).single();
        Long pageSize = jdbc.sql("PRAGMA page_size").query(Long.class).single();
        return pages * pageSize;
    }
}
