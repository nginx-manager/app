package net.nginxmanager.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import net.nginxmanager.common.BaseEntity;
import net.nginxmanager.user.User;

@Entity
@Table(name = "audit_log")
public class AuditLog extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /** 사용자가 지워지거나 없는 이름으로 로그인을 시도해도 남도록 이름을 따로 기록한다. */
    @Column(length = 50)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AuditAction action;

    @Column(length = 255)
    private String target;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuditResult result;

    @Column(columnDefinition = "TEXT")
    private String detail;

    protected AuditLog() {
    }

    public AuditLog(User user, String username, AuditAction action, String target, AuditResult result, String detail) {
        this.user = user;
        this.username = username;
        this.action = action;
        this.target = target;
        this.result = result;
        this.detail = detail;
    }

    public User getUser() {
        return user;
    }

    public String getUsername() {
        return username;
    }

    public AuditAction getAction() {
        return action;
    }

    public String getTarget() {
        return target;
    }

    public AuditResult getResult() {
        return result;
    }

    public String getDetail() {
        return detail;
    }
}
