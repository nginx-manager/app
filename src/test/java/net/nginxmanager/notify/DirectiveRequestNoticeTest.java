package net.nginxmanager.notify;

import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.audit.AuditEvent;
import net.nginxmanager.audit.AuditResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 035: 추가 지시어 요청은 알림 채널로 가고, 반영·거절은 가지 않는다. */
class DirectiveRequestNoticeTest {

    @Test
    void requestsAreSentToChannels() {
        NotificationService.Notice notice = OpsNotifier.noticeFor(new AuditEvent("alice", AuditAction.DIRECTIVE_REQUEST,
                "site:docs.local", AuditResult.SUCCESS, "requested: alias, root"));
        assertThat(notice).isNotNull();
        assertThat(notice.event()).isEqualTo("DIRECTIVE_REQUEST");
        assertThat(notice.subject()).contains("docs.local");
        assertThat(notice.message()).contains("alice").contains("alias, root");
    }

    @Test
    void decisionsAreNotSent() {
        assertThat(OpsNotifier.noticeFor(new AuditEvent("admin", AuditAction.DIRECTIVE_REQUEST, "site:docs.local",
                AuditResult.SUCCESS, "approved: alice"))).isNull();
    }
}
