package net.nginxmanager.cert;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AcmeSettingsRepository extends JpaRepository<AcmeSettings, Long> {

    Optional<AcmeSettings> findFirstByOrderByIdAsc();
}
