package net.nginxmanager.stream;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import net.nginxmanager.common.BaseEntity;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * TCP/UDP 스트림 프록시 하나. 설정 파일 {@link StreamPaths#of} 하나와 짝을 이루며, 파일은 경로로 찾는다
 * (삭제를 적용하면 설정 파일 행이 먼저 사라지므로 연관으로 묶지 않는다).
 */
@Entity
@Table(name = "stream_proxy")
public class StreamProxy extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private StreamProtocol protocol;

    @Column(name = "listen_port", nullable = false)
    private int listenPort;

    /** 백엔드 주소(호스트:포트). 줄마다 하나. 원문 전용이면 빈 문자열일 수 있다. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String backends;

    @Enumerated(EnumType.STRING)
    @Column(name = "load_balancing", nullable = false, length = 20)
    private StreamBalancing loadBalancing = StreamBalancing.ROUND_ROBIN;

    /** 초. null이면 nginx 기본값(60초). */
    @Column(name = "connect_timeout")
    private Integer connectTimeout;

    /** 초. 이 시간 동안 양쪽 모두 아무것도 보내지 않으면 연결을 끊는다. null이면 nginx 기본값(10분). */
    @Column(name = "idle_timeout")
    private Integer idleTimeout;

    @Column(length = 200)
    private String note;

    /** 이 주소에서 오는 연결만 받는다(025). 줄마다 하나. */
    @Column(name = "allow_from", columnDefinition = "TEXT")
    private String allowFrom;

    /** 이 주소에서 오는 연결을 막는다(025). 줄마다 하나. */
    @Column(name = "deny_from", columnDefinition = "TEXT")
    private String denyFrom;

    /** 백엔드에 PROXY protocol로 클라이언트 주소를 넘긴다(025). */
    @Column(name = "proxy_protocol", nullable = false)
    private boolean proxyProtocol;

    /** 폼 밖에서 파일이 바뀌어 원문 편집으로만 다루는 스트림. */
    @Column(nullable = false)
    private boolean raw;

    @Column(name = "config_path", nullable = false, unique = true)
    private String configPath;

    /** 마지막으로 nginx에 반영된 폼 값(Properties 형식). 초안을 버리면 이 값으로 되돌린다. */
    @Column(name = "applied_settings", columnDefinition = "TEXT")
    private String appliedSettings;

    protected StreamProxy() {
    }

    StreamProxy(StreamProtocol protocol, int listenPort) {
        this.protocol = protocol;
        this.listenPort = listenPort;
        this.configPath = StreamPaths.of(protocol, listenPort);
        this.backends = "";
    }

    void applyForm(StreamForm form) {
        this.backends = String.join("\n", form.backendList());
        this.loadBalancing = form.loadBalancing();
        this.connectTimeout = form.connectTimeout();
        this.idleTimeout = form.idleTimeout();
        this.note = form.noteNormalized();
        this.allowFrom = joined(form.allowList());
        this.denyFrom = joined(form.denyList());
        this.proxyProtocol = form.proxyProtocol();
    }

    private static String joined(java.util.List<String> addresses) {
        return addresses.isEmpty() ? null : String.join("\n", addresses);
    }

    void markRaw() {
        this.raw = true;
    }

    /** 지금 폼 값이 nginx에 반영되었다. */
    void markApplied() {
        Properties values = new Properties();
        values.setProperty("backends", backends);
        values.setProperty("loadBalancing", loadBalancing.name());
        if (connectTimeout != null) {
            values.setProperty("connectTimeout", connectTimeout.toString());
        }
        if (idleTimeout != null) {
            values.setProperty("idleTimeout", idleTimeout.toString());
        }
        if (note != null) {
            values.setProperty("note", note);
        }
        if (allowFrom != null) {
            values.setProperty("allowFrom", allowFrom);
        }
        if (denyFrom != null) {
            values.setProperty("denyFrom", denyFrom);
        }
        values.setProperty("proxyProtocol", Boolean.toString(proxyProtocol));
        StringWriter out = new StringWriter();
        try {
            values.store(out, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.appliedSettings = out.toString().lines().filter(line -> !line.startsWith("#"))
                .collect(Collectors.joining("\n", "", "\n"));
    }

    /**
     * 버린 초안의 폼 값을 마지막으로 반영된 값으로 되돌린다.
     *
     * @return 반영된 값이 있어 되돌렸으면 true
     */
    boolean restoreApplied() {
        if (appliedSettings == null) {
            return false;
        }
        Properties values = new Properties();
        try {
            values.load(new StringReader(appliedSettings));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        backends = values.getProperty("backends", "");
        loadBalancing = StreamBalancing.valueOf(values.getProperty("loadBalancing"));
        connectTimeout = values.getProperty("connectTimeout") == null ? null
                : Integer.valueOf(values.getProperty("connectTimeout"));
        idleTimeout = values.getProperty("idleTimeout") == null ? null
                : Integer.valueOf(values.getProperty("idleTimeout"));
        note = values.getProperty("note");
        allowFrom = values.getProperty("allowFrom");
        denyFrom = values.getProperty("denyFrom");
        proxyProtocol = Boolean.parseBoolean(values.getProperty("proxyProtocol"));
        return true;
    }

    boolean hasApplied() {
        return appliedSettings != null;
    }

    StreamForm toForm() {
        return new StreamForm(protocol, listenPort, backends, loadBalancing, connectTimeout, idleTimeout, note,
                getVersion(), allowFrom, denyFrom, proxyProtocol);
    }

    public StreamProtocol getProtocol() {
        return protocol;
    }

    public int getListenPort() {
        return listenPort;
    }

    public String getBackends() {
        return backends;
    }

    public java.util.List<String> getBackendList() {
        return backends.isEmpty() ? java.util.List.of() : java.util.List.of(backends.split("\n"));
    }

    public StreamBalancing getLoadBalancing() {
        return loadBalancing;
    }

    public Integer getConnectTimeout() {
        return connectTimeout;
    }

    public Integer getIdleTimeout() {
        return idleTimeout;
    }

    public String getNote() {
        return note;
    }

    public String getAllowFrom() {
        return allowFrom;
    }

    public String getDenyFrom() {
        return denyFrom;
    }

    public boolean isProxyProtocol() {
        return proxyProtocol;
    }

    /** 목록에 보일 접근 제한 여부. */
    public boolean isAccessRestricted() {
        return allowFrom != null || denyFrom != null;
    }

    public boolean isRaw() {
        return raw;
    }

    public String getConfigPath() {
        return configPath;
    }
}
