package net.nginxmanager.api;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import net.nginxmanager.common.BaseEntity;

import java.time.Instant;

/** 배포 스크립트가 쓰는 API 토큰(033). 값은 만들 때 한 번만 보여 주고 해시만 남긴다. */
@Entity
@Table(name = "api_token")
public class ApiToken extends BaseEntity {

    @Column(nullable = false, length = 50, unique = true)
    private String name;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    /** 목록에서 알아볼 수 있게 남기는 앞부분(nmt_ 와 4글자). */
    @Column(name = "token_hint", nullable = false, length = 20)
    private String tokenHint;

    @Column(name = "created_by", length = 50)
    private String createdBy;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected ApiToken() {
    }

    ApiToken(String name, String tokenHash, String tokenHint, String createdBy) {
        this.name = name;
        this.tokenHash = tokenHash;
        this.tokenHint = tokenHint;
        this.createdBy = createdBy;
    }

    void markUsed(Instant at) {
        this.lastUsedAt = at;
    }

    public String getName() {
        return name;
    }

    public String getTokenHint() {
        return tokenHint;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    /** 감사 로그와 적용 기록에 남는 행위자 이름. */
    public String actor() {
        return ApiTokenService.ACTOR_PREFIX + name;
    }
}
