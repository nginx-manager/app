package net.nginxmanager.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import net.nginxmanager.common.BaseEntity;

/**
 * 버전에 남긴 파일 내용(011). 같은 내용은 해시로 한 번만 저장하고 여러 버전 파일이 함께 가리킨다. 바꾸지 않는다.
 */
@Entity
@Table(name = "config_content")
public class ConfigContent extends BaseEntity {

    /** 내용의 SHA-256(16진수). {@link ConfigFileStore#hash(String)}와 같다. */
    @Column(nullable = false, unique = true, length = 64)
    private String hash;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    protected ConfigContent() {
    }

    ConfigContent(String content) {
        this.hash = ConfigFileStore.hash(content);
        this.content = content;
    }

    public String getHash() {
        return hash;
    }

    public String getContent() {
        return content;
    }
}
