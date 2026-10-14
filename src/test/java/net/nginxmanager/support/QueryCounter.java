package net.nginxmanager.support;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실행된 SQL 문 수를 센다. N+1 방지 기준: 목록 조회는 데이터가 1건일 때와 여러 건일 때 쿼리 수가 같아야 한다.
 * 테스트 프로필에서 {@code hibernate.generate_statistics=true}가 켜져 있어야 한다.
 */
@Component
public class QueryCounter {

    private final Statistics statistics;

    public QueryCounter(EntityManagerFactory entityManagerFactory) {
        this.statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    public long count(Runnable action) {
        statistics.clear();
        action.run();
        return statistics.getPrepareStatementCount();
    }

    public <T> long count(Supplier<T> action) {
        return count(() -> {
            var unused = action.get();
        });
    }

    /**
     * 데이터를 small건 만든 상태와 large건 만든 상태에서 같은 조회의 쿼리 수가 같은지 확인한다.
     *
     * @param seed 지정한 건수만큼 데이터를 추가로 만든다
     */
    public void assertConstantQueries(java.util.function.IntConsumer seed, Runnable query, int small, int large) {
        seed.accept(small);
        long withSmall = count(query);
        seed.accept(large - small);
        long withLarge = count(query);
        assertThat(withLarge)
                .as("query count must not grow with data size (N+1): %d rows → %d, %d rows → %d",
                        small, withSmall, large, withLarge)
                .isEqualTo(withSmall);
    }
}
