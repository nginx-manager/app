package net.nginxmanager.site;

import net.nginxmanager.config.ConfigFile;
import net.nginxmanager.config.ConfigFileRepository;
import net.nginxmanager.config.ConfigFileStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Objects;
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
    private final SiteConfigRenderer renderer;

    public SiteReconciler(SiteRepository siteRepository, ConfigFileRepository configFileRepository,
                          ConfigFileStore store, SiteConfigRenderer renderer) {
        this.siteRepository = siteRepository;
        this.configFileRepository = configFileRepository;
        this.store = store;
        this.renderer = renderer;
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
            String rendered = renderer.render(domain, site.toForm());
            if (!Objects.equals(rendered, store.read(site.getConfigFile().getPath()))) {
                site.switchToRaw();
            }
        }
    }

    private Optional<ConfigFile> onDisk(String path) {
        return store.read(path) == null ? Optional.empty() : configFileRepository.findByPath(path);
    }
}
