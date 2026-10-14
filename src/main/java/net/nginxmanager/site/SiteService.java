package net.nginxmanager.site;

import net.nginxmanager.apply.ApplyLock;
import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.audit.AuditEvent;
import net.nginxmanager.cert.CertificateRegistry;
import net.nginxmanager.common.ConcurrentEditException;
import net.nginxmanager.common.NotFoundException;
import net.nginxmanager.config.ConfigFile;
import net.nginxmanager.config.ConfigFileDiscardedEvent;
import net.nginxmanager.config.ConfigFileRepository;
import net.nginxmanager.config.ConfigFileStore;
import net.nginxmanager.config.NewFilePath;
import net.nginxmanager.config.RawEditedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * 사이트 초안 만들기·고치기. 디스크에는 쓰지 않고, 적용(ApplyService) 때 반영된다.
 * 초안을 바꾸는 작업은 적용 잠금 안에서 트랜잭션째 실행해, 적용 중에 저장한 초안이 적용 끝에 함께 비워지지 않게 한다.
 */
@Service
public class SiteService {

    private final SiteRepository siteRepository;
    private final ConfigFileRepository configFileRepository;
    private final ConfigFileStore store;
    private final SiteRendering rendering;
    private final CertificateRegistry certificateRegistry;
    private final ApplicationEventPublisher events;
    private final ApplyLock applyLock;
    private final TransactionTemplate tx;

    public SiteService(SiteRepository siteRepository, ConfigFileRepository configFileRepository,
                       ConfigFileStore store, SiteRendering rendering, CertificateRegistry certificateRegistry,
                       ApplicationEventPublisher events, ApplyLock applyLock, TransactionTemplate tx) {
        this.siteRepository = siteRepository;
        this.configFileRepository = configFileRepository;
        this.store = store;
        this.rendering = rendering;
        this.certificateRegistry = certificateRegistry;
        this.events = events;
        this.applyLock = applyLock;
        this.tx = tx;
    }

    private <T> T locked(Supplier<T> work) {
        return applyLock.withLock(() -> tx.execute(status -> work.get()));
    }

    public Site create(SiteForm form, String actor) {
        return locked(() -> doCreate(form, actor));
    }

    private Site doCreate(SiteForm form, String actor) {
        String domain = form.primaryDomain().toLowerCase(Locale.ROOT);
        String path = SitePaths.of(domain, true);
        if (siteRepository.existsByPrimaryDomain(domain) || configFileRepository.findByPath(path).isPresent()) {
            throw new DuplicateSiteException(domain);
        }
        requireUnusedDomains(null, domain, form);
        ConfigFile file = configFileRepository.save(new ConfigFile(path, true));
        Site site = new Site(domain, form.type(), file);
        site.applyForm(form);
        site.linkCertificate(certificateFor(domain, form));
        stageHtpasswd(site, form);
        siteRepository.save(site);
        file.stageContent(rendering.render(domain, form, site.getCertificateId()));
        events.publishEvent(AuditEvent.success(actor, AuditAction.SITE_SAVE, "site:" + domain));
        return site;
    }

    public Site update(long siteId, SiteForm form, String actor) {
        return locked(() -> doUpdate(siteId, form, actor));
    }

    private Site doUpdate(long siteId, SiteForm form, String actor) {
        Site site = load(siteId);
        requireVersion(site.getVersion(), form.version(), "site " + site.getPrimaryDomain());
        if (site.getType() == SiteType.RAW) {
            throw new IllegalStateException("raw sites are edited in the file editor");
        }
        requireUnusedDomains(site.getId(), site.getPrimaryDomain(), form);
        stageHtpasswd(site, form);
        site.applyForm(form);
        site.linkCertificate(certificateFor(site.getPrimaryDomain(), form));
        site.getConfigFile().stageContent(rendering.render(site.getPrimaryDomain(), form, site.getCertificateId()));
        events.publishEvent(AuditEvent.success(actor, AuditAction.SITE_SAVE, "site:" + site.getPrimaryDomain()));
        return site;
    }

    /** 활성/비활성은 파일을 conf.d/sites 와 sites-disabled 사이로 옮기는 초안이다. */
    public Site setEnabled(long siteId, boolean enabled, long expectedVersion, String actor) {
        return locked(() -> doSetEnabled(siteId, enabled, expectedVersion, actor));
    }

    private Site doSetEnabled(long siteId, boolean enabled, long expectedVersion, String actor) {
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
    public ConfigFile editRaw(long configFileId, String content, long expectedVersion, String actor) {
        return locked(() -> doEditRaw(configFileId, content, expectedVersion, actor));
    }

    private ConfigFile doEditRaw(long configFileId, String content, long expectedVersion, String actor) {
        ConfigFile file = configFileRepository.findById(configFileId)
                .orElseThrow(() -> new NotFoundException("config file " + configFileId));
        requireVersion(file.getVersion(), expectedVersion, "file " + file.getPath());
        file.stageContent(normalizeNewlines(content));
        events.publishEvent(new RawEditedEvent(file.getPath()));
        siteRepository.findByConfigFileId(configFileId)
                .filter(site -> site.getType() != SiteType.RAW)
                .ifPresent(Site::switchToRaw);
        events.publishEvent(AuditEvent.success(actor, AuditAction.SITE_SAVE, "file:" + file.getPath()));
        return file;
    }

    /**
     * 원문 편집기에서 새 파일을 만든다(초안). 경로 형식은 {@link NewFilePath}가 먼저 확인한다.
     *
     * @throws IllegalArgumentException 이미 있는 경로면
     */
    public ConfigFile createFile(String path, String content, String actor) {
        return locked(() -> {
            if (configFileRepository.findByPath(path).isPresent() || store.read(path) != null) {
                throw new IllegalArgumentException("file exists: " + path);
            }
            ConfigFile file = configFileRepository.save(new ConfigFile(path, false));
            file.stageContent(normalizeNewlines(content == null ? "" : content));
            events.publishEvent(AuditEvent.success(actor, AuditAction.SITE_SAVE, "file:" + path + " create"));
            return file;
        });
    }

    /**
     * 사이트에 속하지 않은 파일을 지우는 초안을 만든다. 사이트 파일은 비활성화로, 비밀번호 파일은 사이트 폼으로 다룬다.
     *
     * @throws IllegalStateException 지울 수 없는 파일이면
     */
    public ConfigFile deleteFile(long configFileId, long expectedVersion, String actor) {
        return locked(() -> {
            ConfigFile file = configFileRepository.findById(configFileId)
                    .orElseThrow(() -> new NotFoundException("config file " + configFileId));
            requireVersion(file.getVersion(), expectedVersion, "file " + file.getPath());
            if (!isDeletable(file)) {
                throw new IllegalStateException("file cannot be deleted here: " + file.getPath());
            }
            if (store.read(file.getPath()) == null) {
                // 아직 디스크에 쓰지 않은 새 파일: 초안째 없앤다
                configFileRepository.delete(file);
            } else {
                file.stageDelete();
            }
            events.publishEvent(AuditEvent.success(actor, AuditAction.SITE_SAVE, "file:" + file.getPath() + " delete"));
            return file;
        });
    }

    /** 원문 편집기에서 지울 수 있는 파일인지. */
    public boolean isDeletable(ConfigFile file) {
        return !NewFilePath.isProtected(file.getPath()) && !file.getPath().startsWith(Htpasswd.DIR)
                && siteRepository.findByConfigFileId(file.getId()).isEmpty()
                && SitePaths.domainOf(file.getPath()) == null;
    }

    /** 초안을 버린다. 디스크에 한 번도 쓰지 않은 새 사이트면 사이트 자체를 지운다. */
    public void discard(long configFileId, String actor) {
        locked(() -> {
            doDiscard(configFileId, actor);
            return null;
        });
    }

    private void doDiscard(long configFileId, String actor) {
        ConfigFile file = configFileRepository.findById(configFileId)
                .orElseThrow(() -> new NotFoundException("config file " + configFileId));
        // 사이트 파일과 그 사이트의 비밀번호 파일은 한 번의 저장으로 함께 바뀌므로 함께 버린다
        ConfigFile paired = pairedFile(file);
        discardOne(file);
        if (paired != null && paired.hasPendingChange()) {
            discardOne(paired);
        }
        events.publishEvent(AuditEvent.success(actor, AuditAction.SITE_SAVE, "discard:" + file.getPath()));
    }

    private ConfigFile pairedFile(ConfigFile file) {
        if (file.getPath().startsWith(Htpasswd.DIR)) {
            String domain = file.getPath().substring(Htpasswd.DIR.length());
            return siteRepository.findByPrimaryDomain(domain).map(Site::getConfigFile).orElse(null);
        }
        String domain = SitePaths.domainOf(file.getPath());
        return domain == null ? null : configFileRepository.findByPath(Htpasswd.path(domain)).orElse(null);
    }

    private void discardOne(ConfigFile file) {
        long configFileId = file.getId();
        if (!revertMove(file)) {
            boolean neverWritten = isNeverWritten(file);
            events.publishEvent(new ConfigFileDiscardedEvent(file.getPath(), neverWritten));
            if (neverWritten && file.isDraftDelete()) {
                configFileRepository.delete(file);
            } else if (neverWritten) {
                siteRepository.findByConfigFileId(configFileId).ifPresent(siteRepository::delete);
                configFileRepository.delete(file);
            } else {
                file.discardDraft();
                // 폼 저장은 사이트 값도 바꿔 두므로, 초안을 버리면 마지막으로 반영된 값으로 되돌린다
                siteRepository.findByConfigFileId(configFileId)
                        .filter(site -> site.getType() != SiteType.RAW)
                        .ifPresent(Site::restoreApplied);
            }
        }
    }

    /**
     * 폼의 비밀번호 보호 사용자로 htpasswd 파일 초안을 만든다. 비밀번호는 해시로만 파일과 사이트에 남는다.
     * 사용자를 모두 지우면 파일 삭제 초안을 만든다.
     */
    private void stageHtpasswd(Site site, SiteForm form) {
        String content;
        try {
            content = Htpasswd.merge(site.getAuthHtpasswd(), form.options().authEntries());
        } catch (IllegalArgumentException e) {
            throw new MissingPasswordException(e.getMessage());
        }
        String path = Htpasswd.path(site.getPrimaryDomain());
        ConfigFile file = configFileRepository.findByPath(path).orElse(null);
        String current = file != null && file.getDraftContent() != null ? file.getDraftContent()
                : file != null && file.isDraftDelete() ? null : store.read(path);
        if (java.util.Objects.equals(current, content)) {
            site.updateHtpasswd(content);
            return;
        }
        if (content != null) {
            if (file == null) {
                file = configFileRepository.save(new ConfigFile(path, true));
            }
            file.stageContent(content);
        } else if (file != null) {
            if (store.read(path) == null) {
                configFileRepository.delete(file);
            } else {
                file.stageDelete();
            }
        }
        site.updateHtpasswd(content);
    }

    /**
     * 활성/비활성 전환은 "새 위치에 쓰기"와 "원래 위치 지우기" 두 초안으로 이뤄진다. 그중 하나만 버리면 사이트가
     * 사라지거나(새 위치만 버림) 끈 사이트가 계속 서비스되므로(지우기만 버림), 어느 쪽을 버려도 전환 전체를 되돌린다.
     *
     * @return 전환 중인 파일이라 되돌렸으면 true
     */
    private boolean revertMove(ConfigFile file) {
        String domain = SitePaths.domainOf(file.getPath());
        if (domain == null) {
            return false;
        }
        boolean fileEnabledSide = file.getPath().startsWith(SitePaths.ENABLED_DIR);
        ConfigFile other = configFileRepository.findByPath(SitePaths.of(domain, !fileEnabledSide)).orElse(null);
        Site site = siteRepository.findByPrimaryDomain(domain).orElse(null);
        if (other == null || site == null) {
            return false;
        }
        ConfigFile source;
        ConfigFile target;
        if (file.isDraftDelete() && site.getConfigFile().getId().equals(other.getId()) && other.hasPendingChange()) {
            source = file;
            target = other;
        } else if (other.isDraftDelete() && site.getConfigFile().getId().equals(file.getId())) {
            source = other;
            target = file;
        } else {
            return false;
        }
        source.discardDraft();
        if (isNeverWritten(target)) {
            site.moveTo(source, source.getPath().startsWith(SitePaths.ENABLED_DIR));
            siteRepository.flush();
            configFileRepository.delete(target);
        } else {
            target.discardDraft();
            site.moveTo(source, source.getPath().startsWith(SitePaths.ENABLED_DIR));
        }
        return true;
    }

    private boolean isNeverWritten(ConfigFile file) {
        return file.getLastKnownHash() == null && store.read(file.getPath()) == null;
    }

    /**
     * 이미 서비스 중인 폼 사이트에 Let's Encrypt HTTPS를 켠다. 인증서가 없으면 설정은 그대로이고,
     * 발급이 끝나면 HTTPS 설정이 자동으로 적용된다.
     *
     * @return 발급할 인증서 id
     */
    public long enableLetsEncrypt(long siteId, String actor) {
        return locked(() -> doEnableLetsEncrypt(siteId, actor));
    }

    private long doEnableLetsEncrypt(long siteId, String actor) {
        Site site = load(siteId);
        if (site.getType() == SiteType.RAW) {
            throw new IllegalStateException("raw sites manage TLS in their own config");
        }
        SiteForm form = rendering.formOf(site);
        SiteForm https = form.withLetsEncrypt();
        long certificateId = certificateRegistry.letsEncryptFor(site.getPrimaryDomain(),
                https.certificateDomains(site.getPrimaryDomain()));
        site.applyForm(https);
        site.enableHttps(certificateId);
        if (!site.getConfigFile().hasPendingChange()) {
            // 디스크의 설정은 인증서를 받기 전까지 그대로이므로 지금 값이 반영된 값이다. 발급이 끝나면 이 값으로 HTTPS를 더한다
            site.markApplied();
        }
        events.publishEvent(AuditEvent.success(actor, AuditAction.SITE_SAVE, "site:" + site.getPrimaryDomain() + " https"));
        return certificateId;
    }

    private Long certificateFor(String domain, SiteForm form) {
        if (!form.httpsEnabled()) {
            return null;
        }
        if (form.certificateId() != null) {
            return certificateRegistry.requireUploaded(form.certificateId());
        }
        return certificateRegistry.letsEncryptFor(domain, form.certificateDomains(domain));
    }

    /**
     * 다른 사이트가 이미 대표·추가 도메인으로 쓰는 이름은 받지 않는다. nginx는 겹치는 server_name을 경고만 하고
     * 한쪽을 무시하므로, 검증을 통과해도 한 사이트가 조용히 그 도메인에 응답하지 않게 된다.
     */
    private void requireUnusedDomains(Long siteId, String primary, SiteForm form) {
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (Site other : siteRepository.findAll()) {
            if (other.getId().equals(siteId)) {
                continue;
            }
            taken.add(other.getPrimaryDomain().toLowerCase(Locale.ROOT));
            if (other.getExtraDomains() != null) {
                for (String extra : other.getExtraDomains().split(" ")) {
                    taken.add(extra.toLowerCase(Locale.ROOT));
                }
            }
        }
        for (String domain : form.certificateDomains(primary.toLowerCase(Locale.ROOT))) {
            if (taken.contains(domain)) {
                throw new DuplicateSiteException(domain);
            }
        }
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
