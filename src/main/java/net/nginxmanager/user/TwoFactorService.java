package net.nginxmanager.user;

import net.nginxmanager.audit.AuditAction;
import net.nginxmanager.audit.AuditEvent;
import net.nginxmanager.common.ConcurrentEditException;
import net.nginxmanager.common.NotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

/** 로그인 2단계 인증(017): 켜기·끄기, 로그인 코드 확인, 복구 코드, 관리자 해제. */
@Service
@Transactional
public class TwoFactorService {

    static final String ISSUER = "nginx manager";
    static final int RECOVERY_CODES = 10;

    /** 코드 확인 결과. LOCKED면 실패가 쌓여 계정이 잠겼다. */
    public enum Result { OK, WRONG, LOCKED }

    /** 켜기 화면에 보일 비밀 키와 QR 코드. */
    public record Setup(String secret, String uri, String qrSvg) {
    }

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final UserSessions sessions;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public TwoFactorService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                            ApplicationEventPublisher events, UserSessions sessions, Clock clock) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public boolean isEnabled(String username) {
        return userRepository.findByUsername(username).map(User::isTotpEnabled).orElse(false);
    }

    /** 켜기 전 비밀 키를 만들거나, 이미 만든 것을 다시 보인다. 켜져 있으면 비어 있다. */
    public Optional<Setup> prepare(String username) {
        User user = find(username);
        if (user.isTotpEnabled()) {
            return Optional.empty();
        }
        if (user.getTotpSecret() == null) {
            user.prepareTotp(Totp.newSecret(random));
        }
        String uri = Totp.uri(ISSUER, username, user.getTotpSecret());
        return Optional.of(new Setup(user.getTotpSecret(), uri, QrCodes.svg(uri)));
    }

    /** 앱이 만든 코드가 맞으면 켜고 복구 코드를 돌려준다(이때 한 번만 보인다). */
    public Optional<List<String>> enable(String username, String code) {
        User user = find(username);
        if (user.isTotpEnabled() || user.getTotpSecret() == null) {
            return Optional.empty();
        }
        OptionalLong step = Totp.verify(user.getTotpSecret(), code, clock.instant().getEpochSecond(), null);
        if (step.isEmpty()) {
            return Optional.empty();
        }
        List<String> codes = newRecoveryCodes();
        user.enableTotp(step.getAsLong(), hash(codes));
        events.publishEvent(AuditEvent.success(username, AuditAction.TWO_FACTOR, "user:" + username + " enable"));
        return Optional.of(codes);
    }

    /** 본인이 끈다. 현재 비밀번호를 확인한다. */
    public boolean disable(String username, String currentPassword) {
        User user = find(username);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            return false;
        }
        user.disableTotp();
        events.publishEvent(AuditEvent.success(username, AuditAction.TWO_FACTOR, "user:" + username + " disable"));
        return true;
    }

    /** 복구 코드를 새로 만든다. 이전 코드는 쓸 수 없게 된다. */
    public Optional<List<String>> regenerateRecoveryCodes(String username, String currentPassword) {
        User user = find(username);
        if (!user.isTotpEnabled() || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            return Optional.empty();
        }
        List<String> codes = newRecoveryCodes();
        user.replaceRecoveryCodes(hash(codes));
        events.publishEvent(AuditEvent.success(username, AuditAction.TWO_FACTOR,
                "user:" + username + " recovery-codes"));
        return Optional.of(codes);
    }

    /**
     * 비밀번호 다음 단계의 코드를 확인한다. 인증 앱 코드나 쓰지 않은 복구 코드를 받는다. 틀리면 비밀번호 실패와 같은 횟수에 세어
     * 잠근다.
     */
    public Result verifyLogin(String username, String input) {
        User user = find(username);
        if (user.isLocked(clock.instant())) {
            return Result.LOCKED;
        }
        OptionalLong step = Totp.verify(user.getTotpSecret(), input, clock.instant().getEpochSecond(),
                user.getTotpLastStep());
        if (step.isPresent()) {
            user.useTotpStep(step.getAsLong());
            return loggedIn(user, null);
        }
        Optional<String> recovery = matchingRecoveryCode(user, input);
        if (recovery.isPresent()) {
            List<String> left = new ArrayList<>(user.recoveryCodeHashes());
            left.remove(recovery.get());
            user.replaceRecoveryCodes(left);
            return loggedIn(user, "recovery-code");
        }
        boolean locked = user.recordLoginFailure(LoginAttemptListener.MAX_ATTEMPTS,
                LoginAttemptListener.LOCK_DURATION, clock.instant());
        events.publishEvent(AuditEvent.failure(username, AuditAction.LOGIN, null, "TwoFactorCode"));
        if (locked) {
            events.publishEvent(AuditEvent.failure(username, AuditAction.ACCOUNT_LOCKED, "user:" + username,
                    "TwoFactorCode"));
            return Result.LOCKED;
        }
        return Result.WRONG;
    }

    /** 관리자가 다른 사용자의 2단계 인증을 해제한다. 그 사용자는 로그아웃된다. */
    public void reset(long userId, long expectedVersion, String actor) {
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("user " + userId));
        if (user.getVersion() != expectedVersion) {
            throw new ConcurrentEditException("user " + user.getUsername());
        }
        user.disableTotp();
        sessions.expire(user.getUsername());
        events.publishEvent(AuditEvent.success(actor, AuditAction.TWO_FACTOR,
                "user:" + user.getUsername() + " reset"));
    }

    /** 서버에서 {@code nm reset-2fa}로 해제한다(관리자도 모두 잃었을 때). 없는 아이디면 false. */
    public boolean resetByOperator(String username) {
        Optional<User> user = userRepository.findByUsername(username);
        if (user.isEmpty()) {
            return false;
        }
        user.get().disableTotp();
        sessions.expire(username);
        events.publishEvent(AuditEvent.success("nm", AuditAction.TWO_FACTOR, "user:" + username + " reset"));
        return true;
    }

    private Result loggedIn(User user, String detail) {
        user.recordLoginSuccess();
        events.publishEvent(detail == null
                ? AuditEvent.success(user.getUsername(), AuditAction.LOGIN, null)
                : AuditEvent.success(user.getUsername(), AuditAction.LOGIN, detail));
        return Result.OK;
    }

    private Optional<String> matchingRecoveryCode(User user, String input) {
        if (input == null) {
            return Optional.empty();
        }
        String normalized = normalizeRecoveryCode(input);
        if (normalized.length() != 10) {
            return Optional.empty();
        }
        return user.recoveryCodeHashes().stream()
                .filter(hash -> passwordEncoder.matches(normalized, hash))
                .findFirst();
    }

    /** 대소문자와 줄표, 공백은 가리지 않는다. */
    static String normalizeRecoveryCode(String input) {
        return input.replaceAll("[\\s-]", "").toLowerCase(Locale.ROOT);
    }

    private List<String> newRecoveryCodes() {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            byte[] bytes = new byte[7];
            random.nextBytes(bytes);
            String raw = Totp.base32(bytes).substring(0, 10).toLowerCase(Locale.ROOT);
            codes.add(raw.substring(0, 5) + "-" + raw.substring(5));
        }
        return codes;
    }

    private List<String> hash(List<String> codes) {
        return codes.stream().map(code -> passwordEncoder.encode(normalizeRecoveryCode(code))).toList();
    }

    private User find(String username) {
        return userRepository.findByUsername(username).orElseThrow();
    }
}
