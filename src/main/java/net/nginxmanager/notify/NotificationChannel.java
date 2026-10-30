package net.nginxmanager.notify;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import net.nginxmanager.common.BaseEntity;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/** 인증서 갱신 실패·만료 임박, 적용 실패 같은 운영 알림을 보낼 곳. */
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

    /** 받을 알림 종류(028). 쉼표로 나눈 이름이고, null이면 모두 받는다(새 종류가 생겨도 받는다). */
    @Column(length = 200)
    private String events;

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

    /** 이 종류의 알림을 받는지. 종류가 없는 알림(시험 알림 등)은 늘 받는다. */
    public boolean receives(NotificationEvent kind) {
        return kind == null || events().contains(kind);
    }

    public Set<NotificationEvent> events() {
        if (events == null) {
            return EnumSet.allOf(NotificationEvent.class);
        }
        Set<NotificationEvent> set = EnumSet.noneOf(NotificationEvent.class);
        Arrays.stream(events.split(",")).filter(name -> !name.isBlank()).forEach(name -> {
            try {
                set.add(NotificationEvent.valueOf(name.trim()));
            } catch (IllegalArgumentException e) {
                // 없어진 종류는 무시한다
            }
        });
        return set;
    }

    void setEvents(Collection<NotificationEvent> kinds) {
        events = kinds.containsAll(EnumSet.allOf(NotificationEvent.class)) ? null
                : kinds.stream().sorted().map(Enum::name).distinct().collect(Collectors.joining(","));
    }
}
