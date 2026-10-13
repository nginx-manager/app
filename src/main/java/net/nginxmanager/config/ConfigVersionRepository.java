package net.nginxmanager.config;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConfigVersionRepository extends JpaRepository<ConfigVersion, Long> {

    Optional<ConfigVersion> findFirstByOrderByIdDesc();
}
