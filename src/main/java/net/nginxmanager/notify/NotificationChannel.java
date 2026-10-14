package net.nginxmanager.notify;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import net.nginxmanager.common.BaseEntity;

/** 인증서 갱신 실패·만료 임박을 알릴 곳. */
@Entity
@Table(name = "notification_channel")
public class NotificationChannel extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChannelType type;

    /** 메일 주소 또는 웹훅 URL. */
    @Column(nullable = false, length = 500)
    private String target;

    @Column(nullable = false)
    private boolean enabled = true;

    protected NotificationChannel() {
    }

    public NotificationChannel(ChannelType type, String target) {
        this.type = type;
        this.target = target;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public ChannelType getType() {
        return type;
    }

    public String getTarget() {
        return target;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
