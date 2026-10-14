package net.nginxmanager.notify;

import net.nginxmanager.support.ConfigRootTest;
import net.nginxmanager.user.Role;
import net.nginxmanager.user.SetupState;
import net.nginxmanager.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;

/** 메일 채널로 알림이 나간다(FR-028). SMTP 서버 대신 보낸 메일을 모아 두는 발송기를 쓴다. */
@Import(MailNotificationTest.RecordingMail.class)
class MailNotificationTest extends ConfigRootTest {

    @TestConfiguration
    static class RecordingMail {
        @Bean
        RecordingMailSender recordingMailSender() {
            return new RecordingMailSender();
        }
    }

    static class RecordingMailSender extends JavaMailSenderImpl {
        final List<SimpleMailMessage> sent = new CopyOnWriteArrayList<>();

        @Override
        public void send(SimpleMailMessage... messages) {
            sent.addAll(List.of(messages));
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    SetupState setupState;
    @Autowired
    NotificationService notificationService;
    @Autowired
    RecordingMailSender mail;

    @BeforeEach
    void setUp() {
        mail.sent.clear();
        userRepository.save(new User("admin", "{noop}unused-password", Role.ADMIN));
        setupState.reset();
    }

    @Test
    void enabledMailChannelsReceiveNotices() {
        channelRepository.save(new NotificationChannel(ChannelType.EMAIL, "ops@example.com"));
        NotificationChannel off = new NotificationChannel(ChannelType.EMAIL, "off@example.com");
        off.setEnabled(false);
        channelRepository.save(off);

        List<NotificationService.Delivery> deliveries = notificationService.sendToAll(new NotificationService.Notice(
                "CERT_RENEW_FAILED", "shop.example.com 인증서 갱신 실패", "발급 한도에 걸렸습니다"));

        assertThat(deliveries).singleElement().satisfies(d -> assertThat(d.error()).isNull());
        assertThat(mail.sent).singleElement().satisfies(m -> {
            assertThat(m.getTo()).containsExactly("ops@example.com");
            assertThat(m.getSubject()).isEqualTo("[nginx manager] shop.example.com 인증서 갱신 실패");
            assertThat(m.getText()).contains("발급 한도");
        });
    }

    @Test
    void testButtonSendsAMailAndReportsIt() throws Exception {
        NotificationChannel channel = channelRepository.save(new NotificationChannel(ChannelType.EMAIL, "ops@example.com"));

        mvc.perform(post("/settings/notifications/" + channel.getId() + "/test").with(csrf())
                        .with(user("admin").roles("ADMIN")))
                .andExpect(flash().attribute("notice", containsString("테스트 알림을 보냈습니다")));

        assertThat(mail.sent).singleElement().satisfies(m -> assertThat(m.getTo()).containsExactly("ops@example.com"));
    }
}
