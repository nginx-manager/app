package net.nginxmanager.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code nm reset-2fa 아이디}가 데이터 폴더에 남긴 reset-2fa 파일을 시작할 때 읽어, 적힌 사용자의 2단계 인증을 끈다(017).
 * 관리자도 인증 앱과 복구 코드를 모두 잃었을 때 서버에서 들어오는 길이다.
 */
@Component
public class TwoFactorResetFile implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TwoFactorResetFile.class);

    private final Path file;
    private final TwoFactorService twoFactor;

    public TwoFactorResetFile(@Value("${nginx-manager.data-dir}") Path dataDir, TwoFactorService twoFactor) {
        this.file = dataDir.resolve("reset-2fa");
        this.twoFactor = twoFactor;
    }

    Path file() {
        return file;
    }

    @Override
    public void run(ApplicationArguments args) {
        process();
    }

    void process() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String username = line.trim();
                if (username.isEmpty()) {
                    continue;
                }
                if (twoFactor.resetByOperator(username)) {
                    log.info("2단계 인증을 해제했습니다: {}", username);
                } else {
                    log.warn("2단계 인증 해제: 없는 사용자입니다: {}", username);
                }
            }
            Files.delete(file);
        } catch (IOException e) {
            log.warn("2단계 인증 해제 파일을 처리하지 못했습니다: {}", e.getMessage());
        }
    }
}
