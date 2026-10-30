package net.nginxmanager.api;

import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.audit.AuditEvent;
import net.nginxmanager.common.NotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/** API 토큰 만들기·지우기와 요청의 토큰 확인(033). */
@Service
public class ApiTokenService {

    static final String PREFIX = "nmt_";
    static final String ACTOR_PREFIX = "token:";
    /** 토큰 이름. 감사 로그의 행위자(token:이름)가 사용자 이름 칸(50자)에 들어가야 한다. */
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,40}");
    /** 마지막 사용 시각은 이만큼 지났을 때만 고친다. 배포 스크립트가 연달아 불러도 매번 쓰지 않게 한다. */
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final ApiTokenRepository repository;
    private final ApplicationEventPublisher events;
    private final SecureRandom random = new SecureRandom();

    public ApiTokenService(ApiTokenRepository repository, ApplicationEventPublisher events) {
        this.repository = repository;
        this.events = events;
    }

    /** 새 토큰과 그 값. 값은 이때 한 번만 알 수 있다. */
    public record Created(ApiToken token, String value) {
    }

    public static class InvalidNameException extends RuntimeException {
    }

    public static class DuplicateNameException extends RuntimeException {
    }

    @Transactional
    public Created create(String name, String actor) {
        String trimmed = name == null ? "" : name.strip();
        if (!NAME.matcher(trimmed).matches()) {
            throw new InvalidNameException();
        }
        if (repository.existsByName(trimmed)) {
            throw new DuplicateNameException();
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String value = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        ApiToken token = repository.save(new ApiToken(trimmed, hash(value), value.substring(0, PREFIX.length() + 4), actor));
        events.publishEvent(AuditEvent.success(actor, AuditAction.API_TOKEN, "token:" + trimmed + " created"));
        return new Created(token, value);
    }

    @Transactional
    public void delete(long id, String actor) {
        ApiToken token = repository.findById(id).orElseThrow(() -> new NotFoundException("api token " + id));
        repository.delete(token);
        events.publishEvent(AuditEvent.success(actor, AuditAction.API_TOKEN, "token:" + token.getName() + " deleted"));
    }

    /** Bearer 값에 맞는 토큰. 맞으면 마지막 사용 시각을 남긴다. */
    @Transactional
    public Optional<ApiToken> authenticate(String value) {
        if (value == null || !value.startsWith(PREFIX) || value.length() > 100) {
            return Optional.empty();
        }
        Optional<ApiToken> token = repository.findByTokenHash(hash(value));
        token.ifPresent(found -> {
            Instant now = Instant.now();
            if (found.getLastUsedAt() == null || found.getLastUsedAt().isBefore(now.minus(TOUCH_INTERVAL))) {
                found.markUsed(now);
            }
        });
        return token;
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
