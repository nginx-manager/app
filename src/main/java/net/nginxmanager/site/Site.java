package net.nginxmanager.site;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import net.nginxmanager.common.BaseEntity;
import net.nginxmanager.config.ConfigFile;

/** nginx server 블록 하나. 설정 파일 하나와 짝을 이룬다. */
@Entity
@Table(name = "site")
public class Site extends BaseEntity {

    @Column(name = "primary_domain", nullable = false, unique = true, length = 253)
    private String primaryDomain;

    /** 공백으로 구분한 추가 server_name. */
    @Column(name = "extra_domains", columnDefinition = "TEXT")
    private String extraDomains;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SiteType type;

    @Column(name = "listen_http_port", nullable = false)
    private int listenHttpPort = 80;

    @Column(name = "static_root")
    private String staticRoot;

    @Column(name = "proxy_target")
    private String proxyTarget;

    @Column(nullable = false)
    private boolean enabled = true;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "config_file_id")
    private ConfigFile configFile;

    protected Site() {
    }

    public Site(String primaryDomain, SiteType type, ConfigFile configFile) {
        this.primaryDomain = primaryDomain;
        this.type = type;
        this.configFile = configFile;
    }

    void applyForm(SiteForm form) {
        this.extraDomains = form.extraDomainsNormalized();
        this.type = form.type();
        this.listenHttpPort = form.listenHttpPort();
        this.staticRoot = form.type() == SiteType.STATIC ? form.staticRoot() : null;
        this.proxyTarget = form.type() == SiteType.PROXY ? form.proxyTarget() : null;
    }

    public void markImportedDisabled() {
        this.enabled = false;
    }

    void switchToRaw() {
        this.type = SiteType.RAW;
    }

    void moveTo(ConfigFile file, boolean enabled) {
        this.configFile = file;
        this.enabled = enabled;
    }

    public SiteForm toForm() {
        return new SiteForm(primaryDomain, extraDomains == null ? "" : extraDomains, type, listenHttpPort,
                staticRoot, proxyTarget, getVersion());
    }

    public String getPrimaryDomain() {
        return primaryDomain;
    }

    public String getExtraDomains() {
        return extraDomains;
    }

    public SiteType getType() {
        return type;
    }

    public int getListenHttpPort() {
        return listenHttpPort;
    }

    public String getStaticRoot() {
        return staticRoot;
    }

    public String getProxyTarget() {
        return proxyTarget;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public ConfigFile getConfigFile() {
        return configFile;
    }
}
