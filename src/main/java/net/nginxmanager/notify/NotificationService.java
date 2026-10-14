package net.nginxmanager.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * 메일(SMTP)과 웹훅으로 알림을 보낸다. 메일은 spring.mail.* 설정이 있을 때만 쓸 수 있다.
 * 한 채널의 실패가 다른 채널 발송을 막지 않는다.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    public record Notice(String event, String subject, String message) {
    }

    /** 채널 하나의 발송 결과. error가 null이면 성공. */
    public record Delivery(NotificationChannel channel, String error) {
    }

    private final NotificationChannelRepository channelRepository;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public NotificationService(NotificationChannelRepository channelRepository,
                               ObjectProvider<JavaMailSender> mailSender) {
        this.channelRepository = channelRepository;
        this.mailSender = mailSender;
    }

    public List<Delivery> sendToAll(Notice notice) {
        return channelRepository.findByEnabledTrue().stream().map(channel -> send(channel, notice)).toList();
    }

    public Delivery send(NotificationChannel channel, Notice notice) {
        try {
            switch (channel.getType()) {
                case EMAIL -> sendMail(channel.getTarget(), notice);
                case WEBHOOK -> sendWebhook(channel.getTarget(), notice);
            }
            return new Delivery(channel, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Delivery(channel, "중단됨");
        } catch (Exception e) {
            log.warn("notification to {} {} failed: {}", channel.getType(), channel.getTarget(), e.getMessage());
            return new Delivery(channel, e.getMessage());
        }
    }

    private void sendMail(String to, Notice notice) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            throw new IllegalStateException("메일 서버(spring.mail.host)가 설정되지 않았습니다.");
        }
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setTo(to);
        mail.setSubject("[nginx manager] " + notice.subject());
        mail.setText(notice.message());
        sender.send(mail);
    }

    private void sendWebhook(String url, Notice notice) throws IOException, InterruptedException {
        String body = "{\"event\":" + json(notice.event()) + ",\"subject\":" + json(notice.subject())
                + ",\"text\":" + json(notice.subject() + "\n" + notice.message())
                + ",\"message\":" + json(notice.message()) + "}";
        HttpResponse<Void> response = http.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() >= 300) {
            throw new IOException("웹훅 응답 " + response.statusCode());
        }
    }

    static String json(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
