package net.nginxmanager.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import net.nginxmanager.common.BaseEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

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

    @Column(name = "totp_secret", length = 64)
    private String totpSecret;

    @Column(name = "totp_enabled", nullable = false)
    private boolean totpEnabled;

    @Column(name = "totp_last_step")
    private Long totpLastStep;

    @Column(name = "recovery_codes")
    private String recoveryCodes;

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

    /** 켜기 전 비밀 키를 둔다(켜기 화면을 다시 열어도 같은 QR 코드). */
    void prepareTotp(String secret) {
        this.totpSecret = secret;
        this.totpEnabled = false;
        this.totpLastStep = null;
    }

    void enableTotp(long usedStep, List<String> recoveryCodeHashes) {
        this.totpEnabled = true;
        this.totpLastStep = usedStep;
        this.recoveryCodes = String.join("\n", recoveryCodeHashes);
    }

    void disableTotp() {
        this.totpSecret = null;
        this.totpEnabled = false;
        this.totpLastStep = null;
        this.recoveryCodes = null;
    }

    void useTotpStep(long step) {
        this.totpLastStep = step;
    }

    List<String> recoveryCodeHashes() {
        return recoveryCodes == null || recoveryCodes.isBlank() ? List.of() : List.of(recoveryCodes.split("\n"));
    }

    void replaceRecoveryCodes(List<String> hashes) {
        this.recoveryCodes = hashes.isEmpty() ? null : String.join("\n", hashes);
    }

    public boolean isTotpEnabled() {
        return totpEnabled;
    }

    String getTotpSecret() {
        return totpSecret;
    }

    Long getTotpLastStep() {
        return totpLastStep;
    }

    public int getRecoveryCodesLeft() {
        return recoveryCodeHashes().size();
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
