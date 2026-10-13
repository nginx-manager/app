package net.nginxmanager.startup;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StartupRecordRepository extends JpaRepository<StartupRecord, Long> {

    /** 가장 최근 두 번의 시작(이번, 지난번). */
    List<StartupRecord> findTop2ByOrderByIdDesc();
}
