package net.nginxmanager.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import net.nginxmanager.common.BaseEntity;

@Entity
@Table(name = "config_version_file")
public class ConfigVersionFile extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "config_version_id")
    private ConfigVersion configVersion;

    @Column(nullable = false)
    private String path;

    /** 이 버전에서 파일이 지워졌으면 null. */
    @Column(columnDefinition = "TEXT")
    private String content;

    protected ConfigVersionFile() {
    }

    ConfigVersionFile(ConfigVersion configVersion, String path, String content) {
        this.configVersion = configVersion;
        this.path = path;
        this.content = content;
    }

    public String getPath() {
        return path;
    }

    public String getContent() {
        return content;
    }
}
