package net.nginxmanager.startup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import net.nginxmanager.common.BaseEntity;

import java.util.Objects;

/** 한 번의 시작에서 본 nginx·관리도구 버전과 시작 검사 결과. */
@Entity
@Table(name = "startup_record")
public class StartupRecord extends BaseEntity {

    @Column(name = "nginx_version")
    private String nginxVersion;

    @Column(name = "app_version")
    private String appVersion;

    /** 시작 검사 결과. 검사 결과가 없으면(컨테이너 밖에서 실행) null */
    @Column(name = "config_ok")
    private Boolean configOk;

    protected StartupRecord() {
    }

    public StartupRecord(String nginxVersion, String appVersion, Boolean configOk) {
        this.nginxVersion = nginxVersion;
        this.appVersion = appVersion;
        this.configOk = configOk;
    }

    public boolean sameVersionsAs(StartupRecord other) {
        return Objects.equals(nginxVersion, other.nginxVersion) && Objects.equals(appVersion, other.appVersion);
    }

    public String getNginxVersion() {
        return nginxVersion;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public Boolean getConfigOk() {
        return configOk;
    }
}
