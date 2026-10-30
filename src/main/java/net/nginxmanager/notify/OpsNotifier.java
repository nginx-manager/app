package net.nginxmanager.notify;

import jakarta.annotation.PreDestroy;
import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.audit.AuditEvent;
import net.nginxmanager.audit.AuditResult;
import net.nginxmanager.backup.BackupStatus;
import net.nginxmanager.backup.BackupStatusReader;
import net.nginxmanager.config.VersionResult;
import net.nginxmanager.history.DriftFoundEvent;
import net.nginxmanager.history.DriftItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 운영 중 생긴 일을 알림 채널로 보낸다(028): 적용·롤백 실패, nginx 내려감·재시작, 서버에서 직접 바뀐 설정 파일,
 * 백업 실패, 계정 잠김. 감사 로그와 같은 사건을 듣는다. 웹훅이 느려도 적용·로그인 요청이 기다리지 않도록 따로 보낸다.
 */
@Component
public class OpsNotifier {

    private static final Logger log = LoggerFactory.getLogger(OpsNotifier.class);
    private static final int MAX_DRIFT_LINES = 20;

    private final NotificationService notifications;
    private final BackupStatusReader backupStatus;
    private final ExecutorService sender = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ops-notifier");
        thread.setDaemon(true);
        return thread;
    });

    /** 마지막으로 알린 백업 실패 시각. 같은 실패를 다시 알리지 않는다. */
    private volatile Instant backupFailureReported;

    public OpsNotifier(NotificationService notifications, BackupStatusReader backupStatus) {
        this.notifications = notifications;
        this.backupStatus = backupStatus;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION, fallbackExecution = true)
    public void onAudit(AuditEvent event) {
        NotificationService.Notice notice = noticeFor(event);
        if (notice != null) {
            send(notice);
        }
    }

    static NotificationService.Notice noticeFor(AuditEvent event) {
        String actor = event.username() == null ? "시스템" : event.username();
        String detail = Objects.toString(event.detail(), "");
        if ((event.action() == AuditAction.APPLY || event.action() == AuditAction.ROLLBACK)
                && event.result() == AuditResult.FAILURE) {
            String what = event.action() == AuditAction.ROLLBACK ? "롤백" : "설정 적용";
            return new NotificationService.Notice("APPLY_FAILED", what + " 실패",
                    actor + "의 " + what + "이 실패했습니다: " + reason(detail)
                            + "\n적용 화면과 감사 로그에서 확인하세요.");
        }
        if (event.action() == AuditAction.NGINX_RESTART) {
            boolean down = event.result() == AuditResult.FAILURE;
            return new NotificationService.Notice(down ? "NGINX_DOWN" : "NGINX_RESTARTED",
                    down ? "nginx가 내려갔습니다" : "nginx가 다시 시작됐습니다", detail);
        }
        if (event.action() == AuditAction.ACCOUNT_LOCKED) {
            String username = event.target() != null && event.target().startsWith("user:")
                    ? event.target().substring("user:".length()) : actor;
            return new NotificationService.Notice("ACCOUNT_LOCKED", "계정 잠김: " + username,
                    username + " 계정이 로그인 실패가 이어져 잠겼습니다(" + detail + "). 본인이 아니라면 비밀번호를 바꾸세요.");
        }
        return null;
    }

    private static String reason(String detail) {
        for (VersionResult result : VersionResult.values()) {
            if (result.name().equals(detail)) {
                return result.label() + (result == VersionResult.AUTO_RECOVERED
                        ? " (적용 뒤 nginx 상태 확인에 실패해 이전 설정으로 되돌렸습니다)" : " (이전 설정을 그대로 씁니다)");
            }
        }
        return detail.isEmpty() ? "알 수 없는 오류" : detail;
    }

    @EventListener
    public void onDrift(DriftFoundEvent event) {
        String files = event.items().stream().limit(MAX_DRIFT_LINES)
                .map(item -> label(item.kind()) + " " + item.path())
                .collect(Collectors.joining("\n"));
        if (event.items().size() > MAX_DRIFT_LINES) {
            files += "\n… 외 " + (event.items().size() - MAX_DRIFT_LINES) + "개";
        }
        send(new NotificationService.Notice("DRIFT_DETECTED", "서버에서 직접 바뀐 설정 파일 " + event.items().size() + "개",
                "관리도구를 거치지 않고 바뀐 설정 파일이 있습니다. 버전 이력 화면에서 가져오거나 되돌리세요.\n" + files));
    }

    private static String label(DriftItem.Kind kind) {
        return switch (kind) {
            case MODIFIED -> "바뀜";
            case ADDED -> "생김";
            case DELETED -> "지워짐";
        };
    }

    /** {@code nm backup}이 남긴 결과에 새 실패가 있으면 한 번 알린다. */
    @Scheduled(initialDelayString = "${nginx-manager.notify.backup-check:PT10M}",
            fixedDelayString = "${nginx-manager.notify.backup-check:PT10M}")
    public void checkBackup() {
        BackupStatus status = backupStatus.read();
        if (!status.recorded() || status.ok() || status.at().equals(backupFailureReported)) {
            return;
        }
        backupFailureReported = status.at();
        send(new NotificationService.Notice("BACKUP_FAILED", "백업 실패",
                status.at() + "에 " + (status.auto() ? "자동 " : "") + "백업이 실패했습니다"
                        + (status.reason() == null || status.reason().isBlank() ? "" : "(" + status.reason() + ")")
                        + ". 서버의 디스크 공간과 sudo /opt/nginx-manager/nm backup 결과를 확인하세요."));
    }

    private void send(NotificationService.Notice notice) {
        try {
            sender.execute(() -> {
                try {
                    notifications.sendToAll(notice);
                } catch (RuntimeException e) {
                    log.warn("could not send {} notification: {}", notice.event(), e.getMessage());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.debug("notifier stopped; dropping {}", notice.event());
        }
    }

    @PreDestroy
    void stop() {
        sender.shutdown();
    }
}
