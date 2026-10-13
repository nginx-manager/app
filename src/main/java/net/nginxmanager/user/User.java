package net.nginxmanager.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import net.nginxmanager.common.BaseEntity;

import java.time.Duration;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User extends BaseEntity {

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    protected User() {
    }

    public User(String username, String passwordHash, Role role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public boolean isLockedNow() {
        return isLocked(Instant.now());
    }

    /** 실패 횟수를 올리고, 한도에 닿으면 잠근 뒤 횟수를 초기화한다. 잠겼으면 true. */
    public boolean recordLoginFailure(int maxAttempts, Duration lockDuration, Instant now) {
        failedLoginCount++;
        if (failedLoginCount >= maxAttempts) {
            lockedUntil = now.plus(lockDuration);
            failedLoginCount = 0;
            return true;
        }
        return false;
    }

    void changeRole(Role role) {
        this.role = role;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
        this.failedLoginCount = 0;
        this.lockedUntil = null;
    }

    public void recordLoginSuccess() {
        failedLoginCount = 0;
        lockedUntil = null;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getFailedLoginCount() {
        return failedLoginCount;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }
}
