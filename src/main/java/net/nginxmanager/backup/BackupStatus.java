package net.nginxmanager.backup;

import java.time.Duration;
import java.time.Instant;

/**
 * 호스트의 {@code nm backup}이 남긴 마지막 백업 결과와 자동 백업 설정(015).
 *
 * @param at       마지막 백업을 시도한 시각. 기록이 없으면 null
 * @param ok       성공했는지
 * @param file     백업 파일 이름(backups/ 아래)
 * @param bytes    백업 파일 크기
 * @param label    nm backup --label 값(auto, before-v1.3.0 …). 손으로 돌렸으면 빈 값
 * @param reason   실패 이유(no-space, archive)
 * @param schedule 자동 백업 시각(HH:MM). 꺼져 있으면 null
 */
public record BackupStatus(Instant at, boolean ok, String file, long bytes, String label, String reason,
                           String schedule) {

    /** 하루 한 번이라 한 번 빠진 것은 넘기고, 두 번 빠지면 알린다. */
    static final Duration STALE_AFTER = Duration.ofHours(48);

    public boolean recorded() {
        return at != null;
    }

    public boolean auto() {
        return "auto".equals(label);
    }

    /** 마지막 백업이 실패했거나, 자동 백업이 켜졌는데 48시간 넘게 백업이 없다. */
    public boolean needsAttention(Instant now) {
        if (recorded() && !ok) {
            return true;
        }
        return schedule != null && (!recorded() || at.plus(STALE_AFTER).isBefore(now));
    }
}
