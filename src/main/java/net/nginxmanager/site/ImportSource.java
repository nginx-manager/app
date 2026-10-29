package net.nginxmanager.site;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import net.nginxmanager.common.BaseEntity;

/** 등록해 둔 가져오기 위치. 절대 경로 또는 설정 루트 기준 상대 경로이고, glob(* ? ** [] {})을 쓸 수 있다. */
@Entity
@Table(name = "import_source")
public class ImportSource extends BaseEntity {

    @Column(nullable = false, unique = true, length = 1024)
    private String pattern;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    protected ImportSource() {
    }

    public ImportSource(String pattern, String createdBy) {
        this.pattern = pattern;
        this.createdBy = createdBy;
    }

    public String getPattern() {
        return pattern;
    }

    public String getCreatedBy() {
        return createdBy;
    }
}
