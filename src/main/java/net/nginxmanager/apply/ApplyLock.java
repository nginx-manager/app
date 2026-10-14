package net.nginxmanager.apply;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 적용과 초안 변경이 겹치지 않게 하는 잠금. 적용은 초안을 읽고 반영한 뒤 비우는데, 그 사이에 저장한 초안이
 * 함께 비워지지 않도록 초안을 바꾸는 작업도 이 잠금 안에서(트랜잭션 전체를 감싸) 실행한다.
 */
@Component
public class ApplyLock {

    private final ReentrantLock lock = new ReentrantLock();

    /** 잠금 안에서 실행한다. 이미 적용 중이면 기다리지 않고 거절한다. */
    public <T> T withLock(Supplier<T> work) {
        if (!lock.tryLock()) {
            throw new ApplyInProgressException();
        }
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    /** 잠금을 얻으면 실행하고, 적용 중이면 건너뛴다. */
    public <T> Optional<T> tryWithLock(Supplier<T> work) {
        if (!lock.tryLock()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(work.get());
        } finally {
            lock.unlock();
        }
    }
}
