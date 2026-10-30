package net.nginxmanager.site;

import net.nginxmanager.admin.AdminDomainProperties;
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
import net.nginxmanager.user.User;
import net.nginxmanager.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
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
    private final List<OtherTcpPorts> otherTcpPorts;
    private final SiteReconciler reconciler;
    private final SiteImporter importer;
    private final UserRepository userRepository;

    public SiteService(SiteRepository siteRepository, ConfigFileRepository configFileRepository,
                       ConfigFileStore store, SiteRendering rendering, CertificateRegistry certificateRegistry,
                       ApplicationEventPublisher events, ApplyLock applyLock, TransactionTemplate tx,
                       List<OtherTcpPorts> otherTcpPorts, SiteReconciler reconciler, SiteImporter importer,
                       UserRepository userRepository, AdminDomainProperties adminDomain) {
        this.userRepository = userRepository;
        this.adminDomain = adminDomain;
        this.siteRepository = siteRepository;
        this.configFileRepository = configFileRepository;
        this.store = store;
        this.rendering = rendering;
        this.certificateRegistry = certificateRegistry;
        this.events = events;
        this.applyLock = applyLock;
        this.tx = tx;
        this.otherTcpPorts = otherTcpPorts;
        this.reconciler = reconciler;
        this.importer = importer;
    }

    private final AdminDomainProperties adminDomain;

    private <T> T locked(Supplier<T> work) {
        return applyLock.withLock(() -> tx.execute(status -> work.get()));
    }

    public Site create(SiteForm form, String actor) {
        return create(form, actor, null);
    }

    /** @param ownerId 담당 사용자. null이면 담당자 없이(관리자만 보는 사이트로) 만든다 */
    public Site create(SiteForm form, String actor, Long ownerId) {
        return locked(() -> doCreate(form, actor, ownerId));
    }

    private Site doCreate(SiteForm form, String actor, Long ownerId) {
        String domain = form.primaryDomain().toLowerCase(Locale.ROOT);
        String path = SitePaths.of(domain, true);
        if (siteRepository.existsByPrimaryDomain(domain) || configFileRepository.findByPath(path).isPresent()) {
            throw new DuplicateSiteException(domain);
        }
        requireUnusedDomains(null, form.certificateDomains(domain));
        requireFreePort(form.listenHttpPort());
        ConfigFile file = configFileRepository.save(new ConfigFile(path, true));
        Site site = new Site(domain, form.type(), file);
        site.assignOwner(ownerId);
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
            throw new RawSiteFormException();
        }
        requireUnusedDomains(site.getId(), form.certificateDomains(site.getPrimaryDomain().toLowerCase(Locale.ROOT)));
        if (form.listenHttpPort() != site.getListenHttpPort()) {
            requireFreePort(form.listenHttpPort());
        }
        stageHtpasswd(site, form);
        site.applyForm(form);
        site.linkCertificate(certificateFor(site.getPrimaryDomain(), form));
        site.getConfigFile().stageContent(rendering.render(site.getPrimaryDomain(), form, site.getCertificateId()));
        events.publishEvent(AuditEvent.success(actor, AuditAction.SITE_SAVE, "site:" + site.getPrimaryDomain()));
        return site;
    }

    /** 담당자를 바꾼다. 설정 파일과 초안은 그대로이고, 초안은 사이트를 따라 새 담당자에게 넘어간다. */
    public Site assignOwner(long siteId, Long ownerId, long expectedVersion, String actor) {
        return locked(() -> doAssignOwner(siteId, ownerId, expectedVersion, actor));
    }

    private Site doAssignOwner(long siteId, Long ownerId, long expectedVersion, String actor) {
        Site site = load(siteId);
        requireVersion(site.getVersion(), expectedVersion, "site " + site.getPrimaryDomain());
        String before = ownerName(site.getOwnerId());
        String after = ownerName(ownerId);
        if (ownerId != null && after == null) {
            throw new NotFoundException("user " + ownerId);
        }
        if (java.util.Objects.equals(site.getOwnerId(), ownerId)) {
            return site;
        }
        site.assignOwner(ownerId);
        events.publishEvent(AuditEvent.success(actor, AuditAction.SITE_ASSIGN,
                "site:" + site.getPrimaryDomain() + " owner:" + (before == null ? "-" : before) + "->"
                        + (after == null ? "-" : after)));
        return site;
    }

    private String ownerName(Long userId) {
        return userId == null ? null : userRepository.findById(userId).map(User::getUsername).orElse(null);
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

    /**
     * 사이트를 지우는 초안을 만든다(013). 사이트 파일과 비밀번호 파일을 지우는 초안이며, 적용하면 {@link SiteReconciler}가
     * 파일이 사라진 사이트를 지운다. 디스크에 한 번도 쓰지 않은 사이트는 사이트째 없앤다.
     *
     * @return 사이트가 바로 없어졌으면 true(한 번도 적용하지 않은 사이트), 적용을 기다리면 false
     * @throws SitePendingException 사이트 파일이나 비밀번호 파일에 적용하지 않은 변경이 있으면
     */
    public boolean delete(long siteId, long expectedVersion, String actor) {
        return Boolean.TRUE.equals(locked(() -> {
            Site site = load(siteId);
            requireVersion(site.getVersion(), expectedVersion, "site " + site.getPrimaryDomain());
            ConfigFile file = site.getConfigFile();
            String domain = site.getPrimaryDomain();
            if (isNeverWritten(file)) {
                doDiscard(file.getId(), actor);
                events.publishEvent(AuditEvent.success(actor, AuditAction.SITE_DELETE, "site:" + domain));
                return true;
            }
            ConfigFile htpasswd = configFileRepository.findByPath(Htpasswd.path(domain)).orElse(null);
            if (file.hasPendingChange() || (htpasswd != null && htpasswd.hasPendingChange())
                    || configFileRepository.findByPath(SitePaths.of(domain, !site.isEnabled()))
                            .filter(ConfigFile::hasPendingChange).isPresent()) {
                throw new SitePendingException(domain);
            }
            file.stageDelete();
            if (htpasswd != null && store.read(htpasswd.getPath()) != null) {
                htpasswd.stageDelete();
            }
            events.publishEvent(AuditEvent.success(actor, AuditAction.SITE_DELETE, "site:" + domain));
            return false;
        }));
    }

    /**
     * 가져올 파일.
     *
     * @param sourcePath   화면에 보일 원본 위치(절대 경로, 올린 파일이면 파일 이름)
     * @param rootRelative 원본이 설정 루트 안이면 루트 기준 경로, 아니면 null
     * @param content      원본 내용
     * @param primaryDomain 운영자가 고른 대표 도메인. 비우면 server_name의 첫 도메인
     * @param asForm       폼 사이트로 등록한다(폼으로 옮길 수 있을 때만)
     * @param removeSource 설정 루트 안의 원본을 지우는 초안을 함께 만든다
     */
    public record ImportCommand(String sourcePath, String rootRelative, String content, String primaryDomain,
                                boolean asForm, boolean removeSource) {
    }

    /**
     * 설정 파일을 사이트로 가져온다(초안). 폼으로 옮길 수 있고 asForm이면 폼 사이트로, 아니면 원문 그대로 원문 사이트로
     * {@code conf.d/sites/<대표 도메인>.conf}에 만든다. 원본을 지우면 그 삭제 초안이 사이트 초안과 짝이 된다.
     *
     * @throws SiteImportException 사이트로 등록할 수 없으면
     * @throws DuplicateSiteException 도메인이 다른 사이트와 겹치면
     * @throws PortInUseException HTTP 포트를 스트림이 쓰면
     */
    public Site importSite(ImportCommand command, String actor) {
        return locked(() -> doImport(command, actor));
    }

    private Site doImport(ImportCommand command, String actor) {
        SiteImportAnalysis analysis = importer.analyze(command.content(), command.primaryDomain());
        if (!analysis.importable()) {
            throw new SiteImportException(analysis.errorKey(), analysis.errorArg());
        }
        String domain = analysis.primaryDomain();
        if (domain == null) {
            throw new SiteImportException("import.error.domainRequired", null);
        }
        if (!domain.matches(SiteForm.DOMAIN)) {
            throw new SiteImportException("import.error.domainInvalid", domain);
        }
        ConfigFile source = null;
        if (command.removeSource() && command.rootRelative() != null) {
            source = configFileRepository.findByPath(command.rootRelative()).orElse(null);
            if (source != null && source.hasPendingChange()) {
                throw new SiteImportException("import.error.sourcePending", command.rootRelative());
            }
        }
        Site site;
        if (command.asForm()) {
            if (!analysis.formPossible()) {
                throw new SiteImportException("import.error.formNotPossible", null);
            }
            site = doCreate(analysis.form(), actor, null);
        } else {
            String path = SitePaths.of(domain, true);
            if (siteRepository.existsByPrimaryDomain(domain) || configFileRepository.findByPath(path).isPresent()
                    || store.read(path) != null) {
                throw new DuplicateSiteException(domain);
            }
            requireUnusedDomains(null, analysis.domains());
            requireFreePort(analysis.listenPort());
            ConfigFile file = configFileRepository.save(new ConfigFile(path, false));
            site = new Site(domain, SiteType.RAW, file);
            site.readRawListenPort(command.content());
            siteRepository.save(site);
            file.stageContent(normalizeNewlines(command.content()));
        }
        site.markImportedFrom(command.sourcePath(), ConfigFileStore.hash(command.content()),
                String.join(" ", analysis.extraDomains(domain)));
        if (command.removeSource() && command.rootRelative() != null) {
            if (source == null) {
                source = configFileRepository.save(new ConfigFile(command.rootRelative(), false));
                source.markImported(ConfigFileStore.hash(store.read(command.rootRelative())));
            }
            source.stageDelete();
            site.getConfigFile().markImportedFrom(command.rootRelative());
        }
        events.publishEvent(AuditEvent.success(actor, AuditAction.SITE_IMPORT,
                "site:" + domain + " from " + command.sourcePath() + (command.asForm() ? " (form)" : " (raw)")));
        return site;
    }

    /**
     * 원본이 바뀐 가져온 사이트를 다시 가져온다(초안). 폼 사이트이고 새 내용도 폼으로 옮길 수 있으면 폼 값을 바꾸고
     * (HTTPS와 폼에만 있는 옵션은 그대로 둔다), 아니면 원문 그대로 바꿔 원문 사이트가 된다.
     */
    public Site reimport(long siteId, String content, long expectedVersion, String actor) {
        return locked(() -> {
            Site site = load(siteId);
            requireVersion(site.getVersion(), expectedVersion, "site " + site.getPrimaryDomain());
            SiteImportAnalysis analysis = importer.analyze(content, site.getPrimaryDomain());
            if (!analysis.importable()) {
                throw new SiteImportException(analysis.errorKey(), analysis.errorArg());
            }
            if (site.getType() != SiteType.RAW && analysis.formPossible()) {
                SiteForm merged = SiteImporter.merge(rendering.formOf(site), analysis.form());
                doUpdate(siteId, merged, actor);
            } else {
                requireUnusedDomains(site.getId(), analysis.domains());
                ConfigFile file = site.getConfigFile();
                String staged = normalizeNewlines(content);
                if (site.getType() == SiteType.RAW && site.isHttpsEnabled()) {
                    // 원본에는 관리도구가 더한 인증 경로와 443 블록이 없으므로 다시 입힌다
                    String previous = file.getDraftContent() != null ? file.getDraftContent() : store.read(file.getPath());
                    if (previous != null) {
                        staged = RawHttps.reapply(previous, staged);
                    }
                }
                file.stageContent(staged);
                if (site.getType() != SiteType.RAW) {
                    site.switchToRaw();
                }
                site.readRawListenPort(content);
            }
            site.markImportedFrom(site.getImportPath(), ConfigFileStore.hash(content),
                    String.join(" ", analysis.extraDomains(site.getPrimaryDomain())));
            events.publishEvent(AuditEvent.success(actor, AuditAction.SITE_IMPORT,
                    "site:" + site.getPrimaryDomain() + " from " + site.getImportPath() + " (again)"));
            return site;
        });
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
        // 가져오기로 만든 사이트 파일과 원본 삭제는 함께 버린다(원본만 지워지거나 같은 server가 두 번 남지 않게)
        if (file.getImportedFrom() != null) {
            return configFileRepository.findByPath(file.getImportedFrom()).orElse(null);
        }
        if (file.isDraftDelete()) {
            ConfigFile importing = configFileRepository.findByImportedFrom(file.getPath()).orElse(null);
            if (importing != null) {
                return importing;
            }
        }
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
                siteRepository.findByConfigFileId(configFileId).ifPresent(site -> {
                    if (site.getType() != SiteType.RAW) {
                        site.restoreApplied();
                    } else {
                        // 원문 편집을 버렸다: 디스크가 폼으로 만든 그대로면 폼 사이트로 돌아간다
                        reconciler.restoreFormIfUnchanged(site);
                    }
                });
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
     * 만들어 둔 폼 사이트에 Let's Encrypt HTTPS를 켠다. 인증서를 받기 전까지 설정은 HTTP 그대로이고,
     * 발급이 끝나면 HTTPS 설정이 자동으로 적용된다.
     *
     * @return 발급할 인증서와, 지금 바로 발급을 시작해도 되는지. 아직 한 번도 적용하지 않은 사이트는 nginx가
     *         인증 요청(/.well-known/acme-challenge/)에 응답하지 못하므로 적용한 뒤에 발급한다
     * @throws IllegalStateException 원문 사이트이거나 와일드카드 도메인이 있으면
     */
    public LetsEncryptRequest enableLetsEncrypt(long siteId, String actor) {
        return locked(() -> doEnableLetsEncrypt(siteId, actor));
    }

    /** @param issueNow false면 사이트를 적용할 때 발급을 시작한다 */
    public record LetsEncryptRequest(long certificateId, boolean issueNow) {
    }

    private LetsEncryptRequest doEnableLetsEncrypt(long siteId, String actor) {
        Site site = load(siteId);
        if (site.getType() == SiteType.RAW) {
            return enableRawLetsEncrypt(site, actor);
        }
        SiteForm form = rendering.formOf(site);
        SiteForm https = form.withLetsEncrypt();
        if (!https.isLetsEncryptDomainsValid()) {
            throw new WildcardLetsEncryptException();
        }
        long certificateId = certificateRegistry.letsEncryptFor(site.getPrimaryDomain(),
                https.certificateDomains(site.getPrimaryDomain()));
        site.applyForm(https);
        site.enableHttps(certificateId);
        ConfigFile file = site.getConfigFile();
        boolean live = site.isEnabled() && store.read(file.getPath()) != null;
        if (!live && file.getDraftContent() != null) {
            // 아직 반영하지 않은 사이트: 초안도 HTTPS를 켠 값으로 바꿔 두면, 적용할 때 발급이 이어진다
            file.stageContent(rendering.render(site));
        } else if (!file.hasPendingChange()) {
            // 디스크의 설정은 인증서를 받기 전까지 그대로이므로 지금 값이 반영된 값이다. 발급이 끝나면 이 값으로 HTTPS를 더한다
            site.markApplied();
        } else if (live) {
            // 서비스 중인 사이트에 적용하지 않은 편집이 있다: 반영된 값에만 HTTPS를 켜 두면 발급이 끝날 때 편집과 따로 적용된다.
            // 초안은 발급이 끝나면 새 인증서 경로로 다시 만들어진다
            site.markAppliedHttps(certificateId);
        }
        events.publishEvent(AuditEvent.success(actor, AuditAction.SITE_SAVE, "site:" + site.getPrimaryDomain() + " https"));
        return new LetsEncryptRequest(certificateId, live);
    }

    /**
     * 원문 사이트: server_name의 도메인으로 인증서를 만들고, HTTP server 블록에 인증 경로가 없으면 더한 초안을 만든다.
     * 그 초안을 적용하면 발급을 시작한다. 인증 경로가 이미 반영돼 있으면 바로 발급한다.
     * 443 server 블록은 발급된 뒤 {@link #addRawHttpsBlock}으로 더한다.
     */
    private LetsEncryptRequest enableRawLetsEncrypt(Site site, String actor) {
        ConfigFile file = site.getConfigFile();
        String disk = store.read(file.getPath());
        String current = file.getDraftContent() != null ? file.getDraftContent() : disk;
        if (current == null) {
            throw new NotFoundException("config file " + file.getPath());
        }
        List<String> names = RawHttps.domains(current);
        String primary = site.getPrimaryDomain();
        if (primary.contains("*")) {
            throw new WildcardLetsEncryptException();
        }
        List<String> domains = new java.util.ArrayList<>(List.of(primary));
        names.stream().filter(n -> !n.equals(primary)).forEach(domains::add);
        long certificateId = certificateRegistry.letsEncryptFor(primary, List.copyOf(domains));
        site.enableHttps(certificateId);
        String withAcme = RawHttps.withAcmeChallenge(current);
        if (!withAcme.equals(current)) {
            file.stageContent(withAcme);
            events.publishEvent(new RawEditedEvent(file.getPath()));
        }
        boolean live = site.isEnabled() && disk != null && disk.equals(withAcme);
        events.publishEvent(AuditEvent.success(actor, AuditAction.SITE_SAVE, "site:" + primary + " https"));
        return new LetsEncryptRequest(certificateId, live);
    }

    /**
     * 원문 사이트에 발급된 인증서로 443 server 블록을 더한 초안을 만든다. 이미 HTTPS server 블록이 있으면 그대로 둔다.
     *
     * @param redirect 본뜬 80 server 블록을 HTTPS로 넘기는 블록으로 바꾼다(인증 경로는 남긴다)
     */
    public boolean addRawHttpsBlock(long siteId, String certPath, String keyPath, boolean redirect, String actor) {
        return Boolean.TRUE.equals(locked(() -> {
            Site site = load(siteId);
            if (site.getType() != SiteType.RAW) {
                throw new NotFoundException("raw site " + siteId);
            }
            ConfigFile file = site.getConfigFile();
            String current = file.getDraftContent() != null ? file.getDraftContent() : store.read(file.getPath());
            if (current == null) {
                return false;
            }
            String updated = RawHttps.withHttps(current, certPath, keyPath, redirect);
            if (updated.equals(current)) {
                return false;
            }
            file.stageContent(updated);
            events.publishEvent(new RawEditedEvent(file.getPath()));
            events.publishEvent(AuditEvent.success(actor, AuditAction.SITE_SAVE, "site:" + site.getPrimaryDomain() + " https" + (redirect ? " redirect" : "")));
            return true;
        }));
    }

    /**
     * 원문 사이트의 server 블록에 사이트별 access_log/error_log를 더한 초안을 만든다(011). 더할 것이 없으면 false.
     */
    public boolean addRawSiteLogs(long siteId, String actor) {
        return Boolean.TRUE.equals(locked(() -> {
            Site site = load(siteId);
            if (site.getType() != SiteType.RAW) {
                throw new NotFoundException("raw site " + siteId);
            }
            ConfigFile file = site.getConfigFile();
            String current = file.getDraftContent() != null ? file.getDraftContent() : store.read(file.getPath());
            if (current == null) {
                return false;
            }
            String updated = RawLogs.withSiteLogs(current, site.getPrimaryDomain());
            if (updated.equals(current)) {
                return false;
            }
            file.stageContent(updated);
            events.publishEvent(new RawEditedEvent(file.getPath()));
            events.publishEvent(AuditEvent.success(actor, AuditAction.SITE_SAVE, "site:" + site.getPrimaryDomain() + " logs"));
            return true;
        }));
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

    private void requireFreePort(int port) {
        for (OtherTcpPorts ports : otherTcpPorts) {
            ports.usedBy(port).ifPresent(usedBy -> {
                throw new PortInUseException(port, usedBy);
            });
        }
    }

    /**
     * 다른 사이트가 이미 대표·추가 도메인으로 쓰는 이름은 받지 않는다. nginx는 겹치는 server_name을 경고만 하고
     * 한쪽을 무시하므로, 검증을 통과해도 한 사이트가 조용히 그 도메인에 응답하지 않게 된다.
     */
    private void requireUnusedDomains(Long siteId, List<String> domains) {
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
        if (adminDomain.enabled()) {
            // 관리 화면이 쓰는 도메인(.env의 NGINX_MANAGER_ADMIN_DOMAIN)
            taken.add(adminDomain.domain());
        }
        for (String domain : domains) {
            if (taken.contains(domain.toLowerCase(Locale.ROOT))) {
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
