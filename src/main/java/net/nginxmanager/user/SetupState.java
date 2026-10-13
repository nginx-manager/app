package net.nginxmanager.user;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/** 최초 관리자가 만들어졌는지 기억해 요청마다 사용자 수를 세지 않게 한다. */
@Component
public class SetupState {

    private final UserRepository userRepository;
    private final AtomicBoolean completed = new AtomicBoolean();

    public SetupState(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public boolean isCompleted() {
        if (completed.get()) {
            return true;
        }
        if (userRepository.count() > 0) {
            completed.set(true);
            return true;
        }
        return false;
    }

    void markCompleted() {
        completed.set(true);
    }

    /** 테스트에서 사용자를 지운 뒤 다시 확인하게 한다. */
    public void reset() {
        completed.set(false);
    }
}
