package net.nginxmanager.site;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SiteRepository extends JpaRepository<Site, Long> {

    boolean existsByPrimaryDomain(String primaryDomain);

    Optional<Site> findByPrimaryDomain(String primaryDomain);

    Optional<Site> findByConfigFileId(Long configFileId);
}
