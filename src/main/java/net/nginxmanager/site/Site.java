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

    /** 백엔드 주소. 여러 개면 줄마다 하나. */
    @Column(name = "proxy_target", columnDefinition = "TEXT")
    private String proxyTarget;

    @Column(name = "proxy_websocket", nullable = false)
    private boolean proxyWebsocket;

    /** 초. null이면 nginx 기본값. */
    @Column(name = "proxy_connect_timeout")
    private Integer proxyConnectTimeout;

    /** 초. null이면 nginx 기본값. */
    @Column(name = "proxy_read_timeout")
    private Integer proxyReadTimeout;

    @Enumerated(EnumType.STRING)
    @Column(name = "proxy_load_balancing", nullable = false, length = 20)
    private LoadBalancing proxyLoadBalancing = LoadBalancing.ROUND_ROBIN;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "https_enabled", nullable = false)
    private boolean httpsEnabled;

    @Column(name = "redirect_http_to_https", nullable = false)
    private boolean redirectHttpToHttps = true;

    /** 연결한 인증서. 연관 대신 id만 두어 목록 조회에서 추가 쿼리가 생기지 않게 한다. */
    @Column(name = "certificate_id")
    private Long certificateId;

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
        boolean proxy = form.type() == SiteType.PROXY;
        this.proxyTarget = proxy ? form.proxyTargetNormalized() : null;
        this.proxyWebsocket = proxy && form.proxyWebsocket();
        this.proxyConnectTimeout = proxy ? form.proxyConnectTimeout() : null;
        this.proxyReadTimeout = proxy ? form.proxyReadTimeout() : null;
        this.proxyLoadBalancing = proxy ? form.proxyLoadBalancing() : LoadBalancing.ROUND_ROBIN;
        this.httpsEnabled = form.httpsEnabled();
        this.redirectHttpToHttps = form.redirectHttpToHttps();
    }

    void linkCertificate(Long certificateId) {
        this.certificateId = certificateId;
    }

    /** 이미 서비스 중인 사이트에서 HTTPS(Let's Encrypt)를 켠다. */
    void enableHttps(Long certificateId) {
        this.httpsEnabled = true;
        this.certificateId = certificateId;
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

    /** @param uploadedCertificateId 연결한 인증서가 업로드한 것이면 그 id, Let's Encrypt면 null */
    public SiteForm toForm(Long uploadedCertificateId) {
        return new SiteForm(primaryDomain, extraDomains == null ? "" : extraDomains, type, listenHttpPort,
                staticRoot, proxyTarget, getVersion(), httpsEnabled, redirectHttpToHttps, uploadedCertificateId,
                proxyWebsocket, proxyConnectTimeout, proxyReadTimeout, proxyLoadBalancing);
    }

    public boolean isHttpsEnabled() {
        return httpsEnabled;
    }

    public boolean isRedirectHttpToHttps() {
        return redirectHttpToHttps;
    }

    public Long getCertificateId() {
        return certificateId;
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

    public boolean isProxyWebsocket() {
        return proxyWebsocket;
    }

    public Integer getProxyConnectTimeout() {
        return proxyConnectTimeout;
    }

    public Integer getProxyReadTimeout() {
        return proxyReadTimeout;
    }

    public LoadBalancing getProxyLoadBalancing() {
        return proxyLoadBalancing;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public ConfigFile getConfigFile() {
        return configFile;
    }
}
