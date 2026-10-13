package net.nginxmanager.site;

import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.audit.AuditEvent;
import net.nginxmanager.common.ConcurrentEditException;
import net.nginxmanager.common.NotFoundException;
import net.nginxmanager.config.ConfigFile;
import net.nginxmanager.config.ConfigFileRepository;
import net.nginxmanager.config.ConfigFileStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 사이트 초안 만들기·고치기. 디스크에는 쓰지 않고, 적용(ApplyService) 때 반영된다. */
@Service
public class SiteService {

    private final SiteRepository siteRepository;
    private final ConfigFileRepository configFileRepository;
    private final ConfigFileStore store;
    private final SiteConfigRenderer renderer;
    private final ApplicationEventPublisher events;

    public SiteService(SiteRepository siteRepository, ConfigFileRepository configFileRepository,
                       ConfigFileStore store, SiteConfigRenderer renderer, ApplicationEventPublisher events) {
        this.siteRepository = siteRepository;
        this.configFileRepository = configFileRepository;
        this.store = store;
        this.renderer = renderer;
        this.events = events;
    }

    @Transactional
    public Site create(SiteForm form, String actor) {
        String domain = form.primaryDomain().toLowerCase();
        String path = SitePaths.of(domain, true);
        if (siteRepository.existsByPrimaryDomain(domain) || configFileRepository.findByPath(path).isPresent()) {
            throw new DuplicateSiteException(domain);
        }
        ConfigFile file = configFileRepository.save(new ConfigFile(path, true));
        file.stageContent(renderer.render(domain, form));
        Site site = new Site(domain, form.type(), file);
        site.applyForm(form);
        siteRepository.save(site);
        events.publishEvent(AuditEvent.success(actor, AuditAction.SITE_SAVE, "site:" + domain));
        return site;
    }

    @Transactional
    public Site update(long siteId, SiteForm form, String actor) {
        Site site = load(siteId);
        requireVersion(site.getVersion(), form.version(), "site " + site.getPrimaryDomain());
        site.applyForm(form);
        site.getConfigFile().stageContent(renderer.render(site.getPrimaryDomain(), form));
        events.publishEvent(AuditEvent.success(actor, AuditAction.SITE_SAVE, "site:" + site.getPrimaryDomain()));
        return site;
    }

    /** 활성/비활성은 파일을 conf.d/sites 와 sites-disabled 사이로 옮기는 초안이다. */
    @Transactional
    public Site setEnabled(long siteId, boolean enabled, long expectedVersion, String actor) {
        Site site = load(siteId);
        requireVersion(site.getVersion(), expectedVersion, "site " + site.getPrimaryDomain());
        if (site.isEnabled() == enabled) {
            return site;
        }
        ConfigFile current = site.getConfigFile();
        String content = current.getDraftContent() != null ? current.getDraftContent() : store.read(current.getPath());
        String targetPath = SitePaths.of(site.getPrimaryDomain(), enabled);
        ConfigFile target = configFileRepository.findByPath(targetPath)
                .orElseGet(() -> configFileRepository.save(new ConfigFile(targetPath, current.isManaged())));
        target.stageContent(content);
        current.stageDelete();
        site.moveTo(target, enabled);
        events.publishEvent(AuditEvent.success(actor, AuditAction.SITE_SAVE,
                "site:" + site.getPrimaryDomain() + (enabled ? " enable" : " disable")));
        return site;
    }

    /** 원문 편집. 폼 사이트의 파일이면 그 사이트는 원문 전용(RAW)으로 바뀐다. */
    @Transactional
    public ConfigFile editRaw(long configFileId, String content, long expectedVersion, String actor) {
        ConfigFile file = configFileRepository.findById(configFileId)
                .orElseThrow(() -> new NotFoundException("config file " + configFileId));
        requireVersion(file.getVersion(), expectedVersion, "file " + file.getPath());
        file.stageContent(normalizeNewlines(content));
        siteRepository.findByConfigFileId(configFileId)
                .filter(site -> site.getType() != SiteType.RAW)
                .ifPresent(Site::switchToRaw);
        events.publishEvent(AuditEvent.success(actor, AuditAction.SITE_SAVE, "file:" + file.getPath()));
        return file;
    }

    /** 초안을 버린다. 디스크에 한 번도 쓰지 않은 새 사이트면 사이트 자체를 지운다. */
    @Transactional
    public void discard(long configFileId, String actor) {
        ConfigFile file = configFileRepository.findById(configFileId)
                .orElseThrow(() -> new NotFoundException("config file " + configFileId));
        boolean neverWritten = file.getLastKnownHash() == null && store.read(file.getPath()) == null;
        if (neverWritten && file.isDraftDelete()) {
            configFileRepository.delete(file);
        } else if (neverWritten) {
            siteRepository.findByConfigFileId(configFileId).ifPresent(siteRepository::delete);
            configFileRepository.delete(file);
        } else {
            file.discardDraft();
        }
        events.publishEvent(AuditEvent.success(actor, AuditAction.SITE_SAVE, "discard:" + file.getPath()));
    }

    private Site load(long siteId) {
        return siteRepository.findById(siteId).orElseThrow(() -> new NotFoundException("site " + siteId));
    }

    private static void requireVersion(long actual, Long expected, String target) {
        if (expected == null || actual != expected) {
            throw new ConcurrentEditException(target);
        }
    }

    private static String normalizeNewlines(String content) {
        String unix = content.replace("\r\n", "\n");
        return unix.endsWith("\n") ? unix : unix + "\n";
    }
}
