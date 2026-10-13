package net.nginxmanager.site;

import net.nginxmanager.cert.CertificateFiles;
import net.nginxmanager.config.ConfigFile;
import net.nginxmanager.config.ConfigFileRepository;
import net.nginxmanager.config.ConfigFileStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Optional;

/**
 * 되돌리기나 외부 변경 가져오기처럼 사이트 폼을 거치지 않고 사이트 파일이 바뀐 뒤, 사이트 목록을 디스크 상태에 맞춘다.
 * <ul>
 *     <li>파일이 사라진 사이트는 반대편 디렉터리(활성↔비활성)에 파일이 있으면 그쪽으로 옮기고, 없으면 지운다.</li>
 *     <li>사이트 없이 생긴 사이트 파일은 원문(RAW) 사이트로 등록한다.</li>
 *     <li>폼 사이트의 파일이 폼으로 만든 내용과 달라지면 원문 사이트로 바꾼다.</li>
 * </ul>
 * 설정 파일 행을 지우기 전에 호출해야 한다(사이트가 그 행을 참조할 수 있다).
 */
@Component
public class SiteReconciler {

    private final SiteRepository siteRepository;
    private final ConfigFileRepository configFileRepository;
    private final ConfigFileStore store;
    private final SiteRendering rendering;
    private final CertificateFiles certificateFiles;

    public SiteReconciler(SiteRepository siteRepository, ConfigFileRepository configFileRepository,
                          ConfigFileStore store, SiteRendering rendering, CertificateFiles certificateFiles) {
        this.siteRepository = siteRepository;
        this.configFileRepository = configFileRepository;
        this.store = store;
        this.rendering = rendering;
        this.certificateFiles = certificateFiles;
    }

    /** @param changedPaths 디스크에서 내용이 바뀌거나 생기거나 사라진 파일 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reconcile(Collection<String> changedPaths) {
        for (String path : changedPaths) {
            String domain = SitePaths.domainOf(path);
            if (domain != null) {
                reconcile(domain);
            }
        }
    }

    private void reconcile(String domain) {
        String enabledPath = SitePaths.of(domain, true);
        String disabledPath = SitePaths.of(domain, false);
        Optional<ConfigFile> enabled = onDisk(enabledPath);
        Optional<ConfigFile> disabled = onDisk(disabledPath);
        Optional<Site> existing = siteRepository.findByPrimaryDomain(domain);

        if (enabled.isEmpty() && disabled.isEmpty()) {
            existing.filter(site -> store.read(site.getConfigFile().getPath()) == null)
                    .ifPresent(siteRepository::delete);
            return;
        }
        // 둘 다 있으면 지금 사이트가 가리키는 쪽을 유지하고, 새로 정할 때는 활성 쪽을 고른다
        Site site = existing.orElse(null);
        if (site == null) {
            ConfigFile file = enabled.orElseGet(disabled::orElseThrow);
            site = new Site(domain, SiteType.RAW, file);
            site.moveTo(file, enabled.isPresent());
            siteRepository.save(site);
            return;
        }
        String current = site.getConfigFile().getPath();
        if (store.read(current) == null) {
            boolean toEnabled = enabled.isPresent();
            site.moveTo(toEnabled ? enabled.get() : disabled.get(), toEnabled);
        } else {
            site.moveTo(site.getConfigFile(), current.equals(enabledPath));
        }
        if (site.getType() != SiteType.RAW) {
            String disk = store.read(site.getConfigFile().getPath());
            if (sameConfig(rendering.render(site), disk)) {
                // 지금 폼 값이 그대로 반영되었다(적용)
                site.markApplied();
            } else if (!sameConfig(rendering.renderApplied(site), disk)) {
                // 폼으로 만들 수 없는 내용이 되었다(되돌리기, 외부 변경)
                site.switchToRaw();
            }
        }
    }

    /**
     * 폼으로 만든 설정과 디스크 내용이 같은지. 인증서 파일 경로(발급마다 바뀌고 되돌리기로 예전 것을 가리킬 수 있다)와
     * 인증서를 기다린다는 안내 주석은 비교하지 않는다.
     */
    private boolean sameConfig(String rendered, String disk) {
        if (disk == null) {
            return false;
        }
        return normalize(rendered).equals(normalize(disk));
    }

    private String normalize(String content) {
        return certificateFiles.maskPaths(content.replace(SiteConfigRenderer.HTTPS_PENDING, ""));
    }

    private Optional<ConfigFile> onDisk(String path) {
        return store.read(path) == null ? Optional.empty() : configFileRepository.findByPath(path);
    }
}
