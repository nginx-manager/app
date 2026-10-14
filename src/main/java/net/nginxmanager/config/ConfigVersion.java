package net.nginxmanager.config;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import net.nginxmanager.common.BaseEntity;
import net.nginxmanager.user.User;

import java.util.ArrayList;
import java.util.List;

/** 적용 한 번의 기록. 적용을 시도한 파일 전체 내용을 함께 남긴다. */
@Entity
@Table(name = "config_version")
public class ConfigVersion extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id")
    private User author;

    /** 시스템 작업이면 null. 사용자가 지워져도 남도록 따로 둔다. */
    @Column(name = "author_name", length = 50)
    private String authorName;

    @Column(length = 500)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VersionSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VersionResult result;

    @Column(name = "validation_output", columnDefinition = "TEXT")
    private String validationOutput;

    @OneToMany(mappedBy = "configVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ConfigVersionFile> files = new ArrayList<>();

    protected ConfigVersion() {
    }

    public ConfigVersion(User author, String message, VersionSource source, VersionResult result,
                         String validationOutput) {
        this.author = author;
        this.authorName = author == null ? null : author.getUsername();
        this.message = message;
        this.source = source;
        this.result = result;
        this.validationOutput = validationOutput;
    }

    public void addFile(String path, String content) {
        files.add(new ConfigVersionFile(this, path, content));
    }

    public String getAuthorName() {
        return authorName;
    }

    public String getMessage() {
        return message;
    }

    public VersionSource getSource() {
        return source;
    }

    public VersionResult getResult() {
        return result;
    }

    public String getValidationOutput() {
        return validationOutput;
    }

    public List<ConfigVersionFile> getFiles() {
        return files;
    }
}
