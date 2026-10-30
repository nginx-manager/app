package net.nginxmanager.notify;

import net.nginxmanager.apply.ApplyService;
import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.audit.AuditEvent;
import net.nginxmanager.audit.AuditResult;
import net.nginxmanager.backup.BackupStatusReader;
import net.nginxmanager.config.InitialImportService;
import net.nginxmanager.site.SiteForm;
import net.nginxmanager.site.SiteService;
import net.nginxmanager.site.SiteType;
import net.nginxmanager.support.ConfigRootTest;
import net.nginxmanager.support.FakeNginxRuntime;
import net.nginxmanager.user.Role;
import net.nginxmanager.user.SetupState;
import net.nginxmanager.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 028: 운영 알림과 채널마다 받을 종류. 메일 채널과 보낸 메일을 모아 두는 발송기로 확인한다. */
@Import(MailNotificationTest.RecordingMail.class)
class OpsNotificationTest extends ConfigRootTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    SetupState setupState;
    @Autowired
    NotificationService notificationService;
    @Autowired
    MailNotificationTest.RecordingMailSender mail;
    @Autowired
    OpsNotifier opsNotifier;
    @Autowired
    ApplyService applyService;
    @Autowired
    SiteService siteService;
    @Autowired
    InitialImportService initialImportService;
    @Autowired
    ApplicationEventPublisher events;
    @Autowired
    BackupStatusReader backupStatusReader;

    @BeforeEach
    void setUp() {
        mail.sent.clear();
        userRepository.save(new User("admin", "{noop}unused-password", Role.ADMIN));
        setupState.reset();
    }

    private NotificationChannel channel(String to, NotificationEvent... kinds) {
        NotificationChannel channel = new NotificationChannel(ChannelType.EMAIL, to);
        if (kinds.length > 0) {
            channel.setEvents(List.of(kinds));
        }
        return channelRepository.save(channel);
    }

    private List<SimpleMailMessage> mailTo(String to) {
        return mail.sent.stream().filter(m -> List.of(m.getTo()).contains(to)).toList();
    }

    @Test
    void channelsReceiveOnlyTheKindsTheyChose() {
        channel("all@example.com");
        channel("apply@example.com", NotificationEvent.APPLY_FAILED);

        notificationService.sendToAll(new NotificationService.Notice("CERT_EXPIRING", "만료", "곧 만료"));
        notificationService.sendToAll(new NotificationService.Notice("APPLY_FAILED", "적용 실패", "실패"));

        assertThat(mailTo("all@example.com")).hasSize(2);
        assertThat(mailTo("apply@example.com")).singleElement()
                .satisfies(m -> assertThat(m.getSubject()).contains("적용 실패"));
    }

    @Test
    void storedKindsRoundTrip() {
        NotificationChannel channel = new NotificationChannel(ChannelType.EMAIL, "x@example.com");
        assertThat(channel.events()).isEqualTo(EnumSet.allOf(NotificationEvent.class));
        channel.setEvents(List.of(NotificationEvent.DRIFT, NotificationEvent.CERT));
        assertThat(channel.events()).containsExactlyInAnyOrder(NotificationEvent.CERT, NotificationEvent.DRIFT);
        assertThat(channel.receives(null)).isTrue();
        channel.setEvents(List.of());
        assertThat(channel.receives(NotificationEvent.CERT)).isFalse();
        channel.setEvents(EnumSet.allOf(NotificationEvent.class));
        assertThat(channel.events()).isEqualTo(EnumSet.allOf(NotificationEvent.class));
        assertThat(NotificationEvent.of("CERT_RENEW_FAILED")).isEqualTo(NotificationEvent.CERT);
        assertThat(NotificationEvent.of("NGINX_DOWN")).isEqualTo(NotificationEvent.NGINX_RESTART);
        assertThat(NotificationEvent.of("TEST")).isNull();
    }

    @Test
    void aFailedApplyIsNotified() {
        channel("ops@example.com");
        siteService.create(new SiteForm("app.local", "", SiteType.PROXY, 80, null, "http://10.0.0.1:3000", null), "admin");
        nginx.validation = () -> FakeNginxRuntime.invalid("/etc/nginx/conf.d/sites/app.local.conf", 3, "unknown directive");

        applyService.applyPending("admin", "틀림");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(mailTo("ops@example.com"))
                .singleElement().satisfies(m -> {
                    assertThat(m.getSubject()).isEqualTo("[nginx manager] 설정 적용 실패");
                    assertThat(m.getText()).contains("admin", "nginx -t 실패");
                }));
    }

    @Test
    void nginxRestartsAndLockedAccountsAreNotified() {
        channel("ops@example.com", NotificationEvent.NGINX_RESTART, NotificationEvent.ACCOUNT_LOCKED);

        events.publishEvent(new AuditEvent(null, AuditAction.NGINX_RESTART, "nginx", AuditResult.FAILURE,
                "nginx 마스터 프로세스(42)가 내려갔습니다."));
        events.publishEvent(AuditEvent.failure("eve", AuditAction.ACCOUNT_LOCKED, "user:eve", "BadCredentialsException"));
        events.publishEvent(AuditEvent.failure("eve", AuditAction.LOGIN, null, "BadCredentialsException"));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(mailTo("ops@example.com"))
                .extracting(SimpleMailMessage::getSubject)
                .containsExactlyInAnyOrder("[nginx manager] nginx가 내려갔습니다", "[nginx manager] 계정 잠김: eve"));
    }

    @Test
    void driftIsNotifiedOncePerChange() {
        channel("ops@example.com", NotificationEvent.DRIFT);
        writeFile("nginx.conf", "events {}\n");
        initialImportService.importIfEmpty();
        driftDetector.scheduledScan();

        writeFile("nginx.conf", "events {}\n# edited by hand\n");
        driftDetector.scheduledScan();
        driftDetector.scheduledScan();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(mailTo("ops@example.com"))
                .singleElement().satisfies(m -> {
                    assertThat(m.getSubject()).contains("직접 바뀐 설정 파일 1개");
                    assertThat(m.getText()).contains("바뀜 nginx.conf");
                }));
    }

    @Test
    void aBackupFailureIsNotifiedOnce() throws Exception {
        channel("ops@example.com", NotificationEvent.BACKUP_FAILED);
        Files.createDirectories(backupStatusReader.statusFile().getParent());
        Files.writeString(backupStatusReader.statusFile(),
                "time=2026-10-30T03:30:05+0900\nresult=failed\nfile=\nbytes=0\nlabel=auto\nreason=no-space\n");

        opsNotifier.checkBackup();
        opsNotifier.checkBackup();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(mailTo("ops@example.com"))
                .singleElement().satisfies(m -> assertThat(m.getText()).contains("자동 백업이 실패", "no-space")));
        Thread.sleep(200);
        assertThat(mailTo("ops@example.com")).hasSize(1);
        Files.delete(backupStatusReader.statusFile());
    }

    @Test
    void adminChoosesKindsOnTheSettingsPage() throws Exception {
        NotificationChannel channel = channel("ops@example.com");

        mvc.perform(get("/settings/notifications").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"APPLY_FAILED\"")));
        mvc.perform(post("/settings/notifications/{id}/events", channel.getId()).with(csrf())
                        .with(user("admin").roles("ADMIN"))
                        .param("events", "APPLY_FAILED", "BACKUP_FAILED"))
                .andExpect(status().is3xxRedirection());

        assertThat(channelRepository.findById(channel.getId()).orElseThrow().events())
                .containsExactlyInAnyOrder(NotificationEvent.APPLY_FAILED, NotificationEvent.BACKUP_FAILED);
    }
}
