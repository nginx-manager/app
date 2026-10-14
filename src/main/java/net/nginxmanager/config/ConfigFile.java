package net.nginxmanager.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import net.nginxmanager.common.BaseEntity;

/**
 * nginx 설정 파일 하나. 실제 내용은 디스크에 있고, 여기에는 적용 전 초안과 마지막으로 알려진 해시만 둔다.
 */
@Entity
@Table(name = "config_file")
public class ConfigFile extends BaseEntity {

    /** 설정 루트(/etc/nginx) 기준 상대 경로. */
    @Column(nullable = false, unique = true)
    private String path;

    /** 적용을 기다리는 새 내용. null이면 바뀐 것이 없다. */
    @Column(name = "draft_content", columnDefinition = "TEXT")
    private String draftContent;

    /** 다음 적용 때 이 파일을 지운다. */
    @Column(name = "draft_delete", nullable = false)
    private boolean draftDelete;

    @Column(name = "last_known_hash", length = 64)
    private String lastKnownHash;

    /** 관리도구가 폼으로 만든 파일이면 true. */
    @Column(nullable = false)
    private boolean managed;

    protected ConfigFile() {
    }

    public ConfigFile(String path, boolean managed) {
        this.path = path;
        this.managed = managed;
    }

    public boolean hasPendingChange() {
        return draftContent != null || draftDelete;
    }

    public void stageContent(String content) {
        this.draftContent = content;
        this.draftDelete = false;
    }

    public void stageDelete() {
        this.draftContent = null;
        this.draftDelete = true;
    }

    public void discardDraft() {
        this.draftContent = null;
        this.draftDelete = false;
    }

    /** 적용이 끝난 뒤 디스크 상태를 기준으로 삼는다. */
    public void markApplied(String hash) {
        this.lastKnownHash = hash;
        discardDraft();
    }

    public void markImported(String hash) {
        this.lastKnownHash = hash;
    }

    public String getPath() {
        return path;
    }

    public String getDraftContent() {
        return draftContent;
    }

    public boolean isDraftDelete() {
        return draftDelete;
    }

    public String getLastKnownHash() {
        return lastKnownHash;
    }

    public boolean isManaged() {
        return managed;
    }
}
