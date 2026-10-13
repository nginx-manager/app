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

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.Properties;

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

    /**
     * 마지막으로 nginx에 반영된 폼 값. 폼을 저장하면 위 열들은 바로 바뀌지만(초안), 초안을 버리면 이 값으로 되돌리고,
     * 인증서 발급·갱신은 이 값으로 설정을 만들어 아직 적용하지 않은 편집이 함께 반영되지 않게 한다.
     */
    @Column(name = "applied_settings", columnDefinition = "TEXT")
    private String appliedSettings;

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

    /** 사이트 파일이 적용되었다: 지금 폼 값이 nginx에 반영된 값이다. */
    public void markApplied() {
        Properties values = new Properties();
        values.setProperty("type", type.name());
        values.setProperty("listenHttpPort", Integer.toString(listenHttpPort));
        values.setProperty("httpsEnabled", Boolean.toString(httpsEnabled));
        values.setProperty("redirectHttpToHttps", Boolean.toString(redirectHttpToHttps));
        values.setProperty("proxyWebsocket", Boolean.toString(proxyWebsocket));
        values.setProperty("proxyLoadBalancing", proxyLoadBalancing.name());
        putIfPresent(values, "extraDomains", extraDomains);
        putIfPresent(values, "staticRoot", staticRoot);
        putIfPresent(values, "proxyTarget", proxyTarget);
        putIfPresent(values, "certificateId", certificateId);
        putIfPresent(values, "proxyConnectTimeout", proxyConnectTimeout);
        putIfPresent(values, "proxyReadTimeout", proxyReadTimeout);
        StringWriter out = new StringWriter();
        try {
            values.store(out, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.appliedSettings = out.toString();
    }

    /**
     * 버린 초안의 폼 값을 마지막으로 반영된 값으로 되돌린다.
     *
     * @return 반영된 값이 기록되어 있어 되돌렸으면 true
     */
    boolean restoreApplied() {
        Properties values = applied();
        if (values == null) {
            return false;
        }
        type = SiteType.valueOf(values.getProperty("type"));
        listenHttpPort = Integer.parseInt(values.getProperty("listenHttpPort"));
        httpsEnabled = Boolean.parseBoolean(values.getProperty("httpsEnabled"));
        redirectHttpToHttps = Boolean.parseBoolean(values.getProperty("redirectHttpToHttps"));
        proxyWebsocket = Boolean.parseBoolean(values.getProperty("proxyWebsocket"));
        proxyLoadBalancing = LoadBalancing.valueOf(values.getProperty("proxyLoadBalancing"));
        extraDomains = values.getProperty("extraDomains");
        staticRoot = values.getProperty("staticRoot");
        proxyTarget = values.getProperty("proxyTarget");
        certificateId = longOrNull(values.getProperty("certificateId"));
        proxyConnectTimeout = intOrNull(values.getProperty("proxyConnectTimeout"));
        proxyReadTimeout = intOrNull(values.getProperty("proxyReadTimeout"));
        return true;
    }

    /** 마지막으로 반영된 폼 값. 기록이 없으면(이전 버전에서 만든 사이트) 지금 값을 쓴다. */
    public SiteForm toAppliedForm(Long uploadedCertificateId) {
        Properties values = applied();
        if (values == null) {
            return toForm(uploadedCertificateId);
        }
        return new SiteForm(primaryDomain, values.getProperty("extraDomains", ""),
                SiteType.valueOf(values.getProperty("type")), Integer.parseInt(values.getProperty("listenHttpPort")),
                values.getProperty("staticRoot"), values.getProperty("proxyTarget"), getVersion(),
                Boolean.parseBoolean(values.getProperty("httpsEnabled")),
                Boolean.parseBoolean(values.getProperty("redirectHttpToHttps")), uploadedCertificateId,
                Boolean.parseBoolean(values.getProperty("proxyWebsocket")),
                intOrNull(values.getProperty("proxyConnectTimeout")), intOrNull(values.getProperty("proxyReadTimeout")),
                LoadBalancing.valueOf(values.getProperty("proxyLoadBalancing")));
    }

    /** 마지막으로 반영된 값에서 쓰던 인증서. 기록이 없으면 지금 값. */
    public Long getAppliedCertificateId() {
        Properties values = applied();
        return values == null ? certificateId : longOrNull(values.getProperty("certificateId"));
    }

    private Properties applied() {
        if (appliedSettings == null) {
            return null;
        }
        Properties values = new Properties();
        try {
            values.load(new StringReader(appliedSettings));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return values;
    }

    private static void putIfPresent(Properties values, String key, Object value) {
        if (value != null) {
            values.setProperty(key, value.toString());
        }
    }

    private static Long longOrNull(String value) {
        return value == null ? null : Long.valueOf(value);
    }

    private static Integer intOrNull(String value) {
        return value == null ? null : Integer.valueOf(value);
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
